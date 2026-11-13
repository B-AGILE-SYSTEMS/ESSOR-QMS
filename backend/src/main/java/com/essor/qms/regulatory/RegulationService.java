package com.essor.qms.regulatory;

import com.essor.qms.records.Actions;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.essor.qms.shared.TabularFile;
import com.essor.qms.shared.TabularFile.ImportReport;
import com.essor.qms.shared.TabularFile.LineError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Veille réglementaire : registre des textes, exigences et applicabilité par site, évaluations périodiques
 * (non conforme → action M1), taux de conformité par site (KPI « conformité réglementaire »), import.
 * Un texte est applicable à un site s'il est en vigueur et qu'au moins une de ses exigences y est applicable.
 * Taux = (conformes + 0,5 × partiels) / (conformes + partiels + non conformes), sur la dernière évaluation de chaque
 * texte applicable ; « na » et textes non évalués hors taux (comptés à part).
 */
@Service
@Transactional
class RegulationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final Set<String> STATUSES = Set.of("en_vigueur", "abroge", "a_venir");
    static final Set<String> RESULTS = Set.of("conforme", "non_conforme", "partiel", "na");
    static final int ACTION_DUE_DAYS = 30;
    static final List<String> IMPORT_COLUMNS = List.of("reference", "titre", "domaine", "juridiction", "publication",
            "application", "resume", "lien", "statut", "exigence_code", "exigence_texte");

    record RequirementInput(String code, String text) {
    }

    record RegulationInput(String reference, String title, String domain, String jurisdiction, LocalDate publishedOn,
                           LocalDate effectiveOn, String summary, String sourceUrl, String status,
                           Integer reviewMonths, List<RequirementInput> requirements) {
    }

    record Applicability(UUID requirementId, UUID siteId, Boolean applicable, String justification) {
    }

    record ActionInput(String title, UUID ownerId, LocalDate dueDate) {
    }

    record EvaluationInput(UUID siteId, String result, String evidence, String comment, ActionInput action) {
    }

    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final AuditLog audit;
    private final Actions actions;

    RegulationService(JdbcClient jdbc, CurrentUser user, AuditLog audit, Actions actions) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
        this.actions = actions;
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ------------------------------------------------------------------ registre

    @Transactional(readOnly = true)
    List<Map<String, Object>> list(String domain, UUID siteId, String status, String q) {
        List<Map<String, Object>> out = jdbc.sql("""
                select * from regulation
                where (:domain = '' or domain = :domain) and (:status = '' or status = :status)
                  and (:q = '' or reference ilike '%' || :q || '%' or title ilike '%' || :q || '%')
                  and (cast(:site as uuid) is null or exists (
                       select 1 from regulation_requirement q join regulation_applicability a on a.requirement_id = q.id
                       where q.regulation_id = regulation.id and a.site_id = cast(:site as uuid) and a.applicable))
                order by domain, reference limit 1000""")
                .param("domain", domain == null ? "" : domain).param("status", status == null ? "" : status)
                .param("q", q == null ? "" : q.strip()).param("site", siteId)
                .query((rs, i) -> view(rs)).list();
        if (siteId != null) {
            out.forEach(r -> r.put("siteEvaluation", lastEvaluation((UUID) r.get("id"), siteId)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    Map<String, Object> get(UUID id) {
        Map<String, Object> m = one(id);
        m.put("requirements", jdbc.sql("""
                select id, code, text, position from regulation_requirement where regulation_id = :id
                order by position, code""").param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    UUID qid = rs.getObject("id", UUID.class);
                    q.put("id", qid);
                    q.put("code", rs.getString("code"));
                    q.put("text", rs.getString("text"));
                    q.put("position", rs.getInt("position"));
                    q.put("applicability", jdbc.sql("""
                            select a.site_id, s.code, s.name, a.applicable, a.justification
                            from regulation_applicability a join site s on s.id = a.site_id
                            where a.requirement_id = :q order by s.code""").param("q", qid)
                            .query((r2, j) -> {
                                Map<String, Object> a = new LinkedHashMap<>();
                                a.put("siteId", r2.getObject(1));
                                a.put("siteCode", r2.getString(2));
                                a.put("siteName", r2.getString(3));
                                a.put("applicable", r2.getBoolean(4));
                                a.put("justification", r2.getString(5));
                                return a;
                            }).list());
                    return q;
                }).list());
        m.put("evaluations", evaluations("e.regulation_id = :id", Map.of("id", id)));
        return m;
    }

    private Map<String, Object> one(UUID id) {
        return jdbc.sql("select * from regulation where id = :id").param("id", id).query((rs, i) -> view(rs))
                .optional().orElseThrow(() -> ApiException.notFound("Texte réglementaire"));
    }

    Map<String, Object> create(RegulationInput in) {
        user.requireManager();
        validate(in);
        if (jdbc.sql("select count(*) from regulation where reference = :r").param("r", in.reference().strip())
                .query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate", "Cette référence existe déjà");
        }
        UUID id = upsert(in);
        audit.record("REGULATION_CREATED", "regulation", id.toString(), null);
        return get(id);
    }

    Map<String, Object> update(UUID id, RegulationInput in) {
        user.requireManager();
        Map<String, Object> before = one(id);
        RegulationInput fixed = new RegulationInput((String) before.get("reference"), in.title(), in.domain(),
                in.jurisdiction(), in.publishedOn(), in.effectiveOn(), in.summary(), in.sourceUrl(), in.status(),
                in.reviewMonths(), in.requirements());
        validate(fixed);
        upsert(fixed);
        audit.record("REGULATION_UPDATED", "regulation", id.toString(), null);
        return get(id);
    }

    /** Crée ou met à jour (clé : référence) ; les exigences sont ajoutées ou mises à jour par code. */
    private UUID upsert(RegulationInput in) {
        UUID id = jdbc.sql("""
                insert into regulation (tenant_id, reference, title, domain, jurisdiction, published_on, effective_on,
                  summary, source_url, status, review_months)
                values (app_current_tenant(), :ref, :title, :domain, :jur, :pub, :eff, :sum, :url, :status, :rev)
                on conflict (tenant_id, reference) do update set title = excluded.title, domain = excluded.domain,
                  jurisdiction = excluded.jurisdiction, published_on = excluded.published_on,
                  effective_on = excluded.effective_on, summary = excluded.summary, source_url = excluded.source_url,
                  status = excluded.status, review_months = excluded.review_months, updated_at = now()
                returning id""")
                .param("ref", in.reference().strip()).param("title", in.title().strip()).param("domain", in.domain())
                .param("jur", in.jurisdiction()).param("pub", in.publishedOn()).param("eff", in.effectiveOn())
                .param("sum", blank(in.summary())).param("url", blank(in.sourceUrl()))
                .param("status", in.status() == null ? "en_vigueur" : in.status())
                .param("rev", in.reviewMonths() == null ? 12 : in.reviewMonths()).query(UUID.class).single();
        if (in.requirements() != null) {
            int pos = 0;
            for (RequirementInput q : in.requirements()) {
                jdbc.sql("""
                        insert into regulation_requirement (tenant_id, regulation_id, code, text, position)
                        values (app_current_tenant(), :reg, :code, :text, :pos)
                        on conflict (tenant_id, regulation_id, code) do update set text = excluded.text,
                          position = excluded.position""")
                        .param("reg", id).param("code", q.code().strip()).param("text", q.text().strip())
                        .param("pos", pos++).update();
            }
        }
        return id;
    }

    private static List<String> errors(RegulationInput in) {
        List<String> e = new ArrayList<>();
        if (in.reference() == null || in.reference().isBlank() || in.reference().length() > 200) {
            e.add("référence obligatoire (200 caractères maximum)");
        }
        if (in.title() == null || in.title().isBlank() || in.title().length() > 500) {
            e.add("titre obligatoire (500 caractères maximum)");
        }
        if (in.domain() == null || in.domain().isBlank() || in.domain().length() > 30) {
            e.add("domaine obligatoire (sst, environnement, qualite, produit...)");
        }
        if (in.jurisdiction() == null || in.jurisdiction().isBlank() || in.jurisdiction().length() > 30) {
            e.add("juridiction obligatoire (Maroc, France, UE...)");
        }
        if (in.status() != null && !STATUSES.contains(in.status())) {
            e.add("statut : en_vigueur, abroge ou a_venir");
        }
        if (in.reviewMonths() != null && (in.reviewMonths() < 1 || in.reviewMonths() > 60)) {
            e.add("périodicité de réévaluation : 1 à 60 mois");
        }
        if (in.sourceUrl() != null && !in.sourceUrl().isBlank() && (in.sourceUrl().length() > 500
                || !in.sourceUrl().matches("^https?://\\S+$"))) {
            e.add("lien source : URL http(s) attendue");
        }
        if (in.requirements() != null) {
            for (RequirementInput q : in.requirements()) {
                if (q == null || q.code() == null || q.code().isBlank() || q.code().length() > 30
                        || q.text() == null || q.text().isBlank()) {
                    e.add("exigence : code (30 caractères maximum) et texte obligatoires");
                    break;
                }
            }
        }
        return e;
    }

    private static void validate(RegulationInput in) {
        List<String> e = errors(in);
        if (!e.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", e));
        }
    }

    Map<String, Object> setApplicability(UUID id, List<Applicability> items) {
        user.requireManager();
        one(id);
        for (Applicability a : items == null ? List.<Applicability>of() : items) {
            if (a.requirementId() == null || a.siteId() == null || a.applicable() == null) {
                throw ApiException.badRequest("requirementId, siteId et applicable obligatoires");
            }
            if (jdbc.sql("select count(*) from regulation_requirement where id = :q and regulation_id = :r")
                    .param("q", a.requirementId()).param("r", id).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Exigence inconnue pour ce texte");
            }
            if (jdbc.sql("select count(*) from site where id = :s").param("s", a.siteId()).query(Long.class)
                    .single() == 0) {
                throw ApiException.badRequest("Site inconnu");
            }
            if (a.justification() != null && a.justification().length() > 1000) {
                throw ApiException.badRequest("Justification : 1000 caractères maximum");
            }
            jdbc.sql("""
                    insert into regulation_applicability (tenant_id, requirement_id, site_id, applicable, justification)
                    values (app_current_tenant(), :q, :s, :a, :j)
                    on conflict (tenant_id, requirement_id, site_id) do update set applicable = excluded.applicable,
                      justification = excluded.justification""")
                    .param("q", a.requirementId()).param("s", a.siteId()).param("a", a.applicable())
                    .param("j", blank(a.justification())).update();
        }
        audit.record("REGULATION_APPLICABILITY_UPDATED", "regulation", id.toString(), null);
        return get(id);
    }

    // ------------------------------------------------------------------ évaluations

    /** Non conforme → action M1 ({@code data.origine = "veille"}) ; échéance = date + périodicité du texte. */
    Map<String, Object> evaluate(UUID id, EvaluationInput in) {
        user.requireWriter();
        Map<String, Object> reg = one(id);
        if (in.result() == null || !RESULTS.contains(in.result())) {
            throw ApiException.badRequest("result : conforme, non_conforme, partiel ou na");
        }
        if (in.siteId() == null || jdbc.sql("select count(*) from site where id = :s").param("s", in.siteId())
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Site inconnu");
        }
        if (len(in.evidence()) > 2000 || len(in.comment()) > 2000) {
            throw ApiException.badRequest("Preuve et commentaire : 2000 caractères maximum");
        }
        LocalDate next = today().plusMonths((Integer) reg.get("reviewMonths"));
        UUID action = null;
        if ("non_conforme".equals(in.result()) || in.action() != null) {
            ActionInput a = in.action();
            action = actions.create(a != null && a.title() != null ? a.title()
                            : "Mise en conformité : " + reg.get("reference"),
                    "Évaluation réglementaire non conforme. " + (in.comment() == null ? "" : in.comment()),
                    in.siteId(), a == null ? null : a.ownerId(),
                    a != null && a.dueDate() != null ? a.dueDate() : today().plusDays(ACTION_DUE_DAYS),
                    "conformite", Map.of("origine", "veille", "regulationId", id,
                            "regulationReference", reg.get("reference")));
        }
        UUID evalId = jdbc.sql("""
                insert into regulation_evaluation (tenant_id, regulation_id, site_id, result, evidence, comment,
                  next_due, action_id, evaluated_by)
                values (app_current_tenant(), :r, :s, :res, :ev, :c, :due, :a, app_current_user()) returning id""")
                .param("r", id).param("s", in.siteId()).param("res", in.result()).param("ev", blank(in.evidence()))
                .param("c", blank(in.comment())).param("due", next).param("a", action).query(UUID.class).single();
        audit.record("REGULATION_EVALUATED", "regulation", id.toString(),
                "{\"evaluationId\":\"" + evalId + "\",\"result\":\"" + in.result() + "\"}");
        return evaluations("e.id = :id", Map.of("id", evalId)).get(0);
    }

    private List<Map<String, Object>> evaluations(String where, Map<String, Object> params) {
        return jdbc.sql("""
                select e.*, s.code s_code, s.name s_name, coalesce(u.display_name, u.username) author,
                  a.reference a_ref, a.status a_status
                from regulation_evaluation e join site s on s.id = e.site_id
                left join app_user u on u.id = e.evaluated_by left join record a on a.id = e.action_id
                where\s""" + where + " order by e.evaluated_at desc").params(params)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("regulationId", rs.getObject("regulation_id"));
                    m.put("site", Map.of("id", rs.getObject("site_id"), "code", rs.getString("s_code"),
                            "name", rs.getString("s_name")));
                    m.put("result", rs.getString("result"));
                    m.put("evidence", rs.getString("evidence"));
                    m.put("comment", rs.getString("comment"));
                    m.put("evaluatedAt", rs.getTimestamp("evaluated_at").toInstant());
                    m.put("evaluatedBy", rs.getString("author"));
                    LocalDate due = rs.getObject("next_due", LocalDate.class);
                    m.put("nextDue", due);
                    m.put("overdue", due.isBefore(today()));
                    m.put("action", rs.getObject("action_id") == null ? null : Map.of("id", rs.getObject("action_id"),
                            "reference", rs.getString("a_ref"), "status", rs.getString("a_status")));
                    return m;
                }).list();
    }

    private Map<String, Object> lastEvaluation(UUID regulationId, UUID siteId) {
        List<Map<String, Object>> l = evaluations("e.regulation_id = :r and e.site_id = :s",
                Map.of("r", regulationId, "s", siteId));
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------------ conformité

    /** Tableau de conformité par site (tous les sites, ou un seul) et taux global. */
    @Transactional(readOnly = true)
    Map<String, Object> compliance(UUID siteId) {
        List<Map<String, Object>> sites = jdbc.sql("""
                with applicable as (
                  select distinct r.id regulation_id, a.site_id from regulation r
                  join regulation_requirement q on q.regulation_id = r.id
                  join regulation_applicability a on a.requirement_id = q.id and a.applicable
                  where r.status = 'en_vigueur'),
                last as (
                  select distinct on (e.regulation_id, e.site_id) e.regulation_id, e.site_id, e.result, e.next_due
                  from regulation_evaluation e order by e.regulation_id, e.site_id, e.evaluated_at desc)
                select s.id, s.code, s.name, count(ap.regulation_id) applicable,
                  count(l.result) evaluated,
                  count(*) filter (where l.result = 'conforme') conforme,
                  count(*) filter (where l.result = 'partiel') partiel,
                  count(*) filter (where l.result = 'non_conforme') non_conforme,
                  count(*) filter (where l.result = 'na') na,
                  count(*) filter (where l.next_due < :today) overdue
                from site s left join applicable ap on ap.site_id = s.id
                left join last l on l.regulation_id = ap.regulation_id and l.site_id = ap.site_id
                where cast(:site as uuid) is null or s.id = cast(:site as uuid)
                group by s.id, s.code, s.name order by s.code""")
                .param("site", siteId).param("today", today())
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("site", Map.of("id", rs.getObject("id"), "code", rs.getString("code"),
                            "name", rs.getString("name")));
                    long applicable = rs.getLong("applicable");
                    long evaluated = rs.getLong("evaluated");
                    long c = rs.getLong("conforme");
                    long p = rs.getLong("partiel");
                    long nc = rs.getLong("non_conforme");
                    m.put("applicable", applicable);
                    m.put("evaluated", evaluated);
                    m.put("notEvaluated", applicable - evaluated);
                    m.put("conforme", c);
                    m.put("partiel", p);
                    m.put("nonConforme", nc);
                    m.put("na", rs.getLong("na"));
                    m.put("overdue", rs.getLong("overdue"));
                    m.put("rate", rate(c, p, nc));
                    return m;
                }).list();
        long c = sites.stream().mapToLong(s -> (Long) s.get("conforme")).sum();
        long p = sites.stream().mapToLong(s -> (Long) s.get("partiel")).sum();
        long nc = sites.stream().mapToLong(s -> (Long) s.get("nonConforme")).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rate", rate(c, p, nc));
        out.put("sites", sites);
        return out;
    }

    static Double rate(long conforme, long partiel, long nonConforme) {
        long den = conforme + partiel + nonConforme;
        if (den == 0) {
            return null;
        }
        return BigDecimal.valueOf(conforme * 100.0 + partiel * 50.0).divide(BigDecimal.valueOf(den), 1,
                RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ import

    /** Une ligne par exigence ; les colonnes du texte sont répétées (la première ligne d'une référence fait foi). */
    ImportReport importFile(byte[] content, boolean dryRun) {
        user.requireManager();
        TabularFile.Table t = TabularFile.read(content, IMPORT_COLUMNS, List.of("reference", "titre", "domaine",
                "juridiction"));
        Set<String> existing = new HashSet<>(jdbc.sql("select reference from regulation").query(String.class).list());
        Map<String, RegulationInput> byRef = new LinkedHashMap<>();
        Map<String, List<RequirementInput>> reqs = new LinkedHashMap<>();
        List<LineError> errors = new ArrayList<>();
        for (int r = 0; r < t.rows().size(); r++) {
            List<String> row = t.rows().get(r);
            int line = r + 2;
            try {
                String ref = t.cell(row, "reference");
                String status = t.cell(row, "statut").toLowerCase(Locale.ROOT);
                RegulationInput in = new RegulationInput(ref, t.cell(row, "titre"),
                        t.cell(row, "domaine").toLowerCase(Locale.ROOT), t.cell(row, "juridiction"),
                        date(t.cell(row, "publication")), date(t.cell(row, "application")), t.cell(row, "resume"),
                        t.cell(row, "lien"), status.isEmpty() ? null : status, null, null);
                List<String> e = errors(in);
                String code = t.cell(row, "exigence_code");
                String text = t.cell(row, "exigence_texte");
                if (code.isEmpty() != text.isEmpty() || code.length() > 30) {
                    e.add("exigence : code (30 caractères maximum) et texte vont ensemble");
                }
                e.forEach(x -> errors.add(new LineError(line, x)));
                byRef.putIfAbsent(ref, in);
                if (!code.isEmpty()) {
                    reqs.computeIfAbsent(ref, k -> new ArrayList<>()).add(new RequirementInput(code, text));
                }
            } catch (RuntimeException e) {
                errors.add(new LineError(line, "date AAAA-MM-JJ attendue"));
            }
        }
        int updated = (int) byRef.keySet().stream().filter(existing::contains).count();
        boolean applied = errors.isEmpty() && !dryRun;
        if (applied) {
            byRef.forEach((ref, in) -> upsert(new RegulationInput(in.reference(), in.title(), in.domain(),
                    in.jurisdiction(), in.publishedOn(), in.effectiveOn(), in.summary(), in.sourceUrl(), in.status(),
                    null, reqs.getOrDefault(ref, List.of()))));
            audit.record("REGULATIONS_IMPORTED", "regulation", null,
                    "{\"created\":" + (byRef.size() - updated) + ",\"updated\":" + updated + "}");
        }
        return new ImportReport(byRef.size() - updated, updated, List.copyOf(errors), applied, dryRun);
    }

    // ------------------------------------------------------------------ outils

    private static Map<String, Object> view(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id"));
        m.put("reference", rs.getString("reference"));
        m.put("title", rs.getString("title"));
        m.put("domain", rs.getString("domain"));
        m.put("jurisdiction", rs.getString("jurisdiction"));
        m.put("publishedOn", rs.getObject("published_on", LocalDate.class));
        m.put("effectiveOn", rs.getObject("effective_on", LocalDate.class));
        m.put("summary", rs.getString("summary"));
        m.put("sourceUrl", rs.getString("source_url"));
        m.put("status", rs.getString("status"));
        m.put("reviewMonths", rs.getInt("review_months"));
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
        return m;
    }

    private static LocalDate date(String v) {
        return v.isEmpty() ? null : LocalDate.parse(v);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
