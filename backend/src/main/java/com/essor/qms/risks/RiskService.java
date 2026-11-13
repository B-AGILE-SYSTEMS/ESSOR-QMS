package com.essor.qms.risks;

import com.essor.qms.records.Actions;
import com.essor.qms.risks.RiskScales.Dimension;
import com.essor.qms.risks.RiskScales.Scale;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.essor.qms.shared.TabularFile;
import com.essor.qms.shared.TabularFile.ImportReport;
import com.essor.qms.shared.TabularFile.LineError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Risques et opportunités : cotation brute / résiduelle et historique, matrice, actions M1, exigences M3, import. */
@Service
@Transactional
class RiskService {

    static final Set<String> TYPES = Set.of("sst", "environnement", "qualite", "opportunite");
    static final List<String> IMPORT_COLUMNS = List.of("type", "site", "unite_travail", "processus", "danger",
            "situation", "mesures", "cotation_brute", "cotation_residuelle", "reevaluation", "echelle");

    record RiskInput(String type, UUID siteId, String scaleCode, String workUnit, String process, String hazard,
                     String situation, String existingMeasures, Map<String, Integer> gross,
                     Map<String, Integer> residual, LocalDate reassessmentDate, List<UUID> requirementIds) {
    }

    record ActionInput(String title, String description, UUID ownerId, LocalDate dueDate, String actionType) {
    }

    private static final String SELECT = """
            select r.*, s.code s_code, s.name s_name, sc.code scale_code from risk r join site s on s.id = r.site_id
            join risk_scale sc on sc.id = r.scale_id
            """;

    private final JdbcClient jdbc;
    private final RiskScales scales;
    private final CurrentUser user;
    private final AuditLog audit;
    private final Actions actions;
    private final ObjectMapper json;

    RiskService(JdbcClient jdbc, RiskScales scales, CurrentUser user, AuditLog audit, Actions actions,
                ObjectMapper json) {
        this.jdbc = jdbc;
        this.scales = scales;
        this.user = user;
        this.audit = audit;
        this.actions = actions;
        this.json = json;
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> list(UUID siteId, String type, String level) {
        return jdbc.sql(SELECT + """
                where (cast(:site as uuid) is null or r.site_id = cast(:site as uuid))
                  and (:type = '' or r.type = :type)
                  and (:level = '' or coalesce(r.residual_level, r.gross_level) = :level)
                order by coalesce(r.residual_score, r.gross_score) desc, r.reference limit 1000""")
                .param("site", siteId).param("type", type == null ? "" : type)
                .param("level", level == null ? "" : level).query((rs, i) -> view(rs)).list();
    }

    @Transactional(readOnly = true)
    Map<String, Object> get(UUID id) {
        Map<String, Object> m = one(id);
        m.put("history", jdbc.sql("""
                select h.at, h.kind, h.ratings, h.score, h.level, coalesce(u.display_name, u.username) author
                from risk_rating_history h left join app_user u on u.id = h.actor_id
                where h.risk_id = :id order by h.at, h.id""").param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("at", rs.getTimestamp("at").toInstant());
                    h.put("kind", rs.getString("kind"));
                    h.put("ratings", readMap(rs.getString("ratings")));
                    h.put("score", rs.getInt("score"));
                    h.put("level", rs.getString("level"));
                    h.put("author", rs.getString("author"));
                    return h;
                }).list());
        return m;
    }

    private Map<String, Object> one(UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id").param("id", id).query((rs, i) -> view(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Risque"));
    }

    Map<String, Object> create(RiskInput in) {
        user.requireWriter();
        Scale scale = scales.byCode(in.scaleCode());
        validate(in);
        Map<String, Object> g = RiskScales.rate(scale, in.gross(), "brute");
        Map<String, Object> r = in.residual() == null || in.residual().isEmpty() ? null
                : RiskScales.rate(scale, in.residual(), "résiduelle");
        String ref = "RSK-" + String.format("%04d", jdbc.sql("select count(*) + 1 from risk").query(Long.class)
                .single());
        UUID id = jdbc.sql("""
                insert into risk (tenant_id, reference, type, site_id, scale_id, work_unit, process, hazard, situation,
                  existing_measures, gross, gross_score, gross_level, residual, residual_score, residual_level,
                  reassessment_date, created_by)
                values (app_current_tenant(), :ref, :type, :site, :scale, :wu, :proc, :haz, :sit, :mes,
                  cast(:g as jsonb), :gs, :gl, cast(:r as jsonb), :rsc, :rl, :rd, app_current_user())
                returning id""")
                .param("ref", ref).param("type", in.type()).param("site", in.siteId()).param("scale", scale.id())
                .param("wu", blank(in.workUnit())).param("proc", blank(in.process())).param("haz", in.hazard().strip())
                .param("sit", blank(in.situation())).param("mes", blank(in.existingMeasures()))
                .param("g", write(g.get("ratings"))).param("gs", g.get("score")).param("gl", g.get("level"))
                .param("r", r == null ? null : write(r.get("ratings"))).param("rsc", r == null ? null : r.get("score"))
                .param("rl", r == null ? null : r.get("level")).param("rd", in.reassessmentDate())
                .query(UUID.class).single();
        history(id, "gross", g);
        if (r != null) {
            history(id, "residual", r);
        }
        links(id, in.requirementIds());
        audit.record("RISK_CREATED", "risk", id.toString(), null);
        return get(id);
    }

    Map<String, Object> update(UUID id, RiskInput in) {
        user.requireWriter();
        Map<String, Object> before = one(id);
        Scale scale = scales.byId((UUID) before.get("scaleId"));
        validate(in);
        Map<String, Object> g = RiskScales.rate(scale, in.gross(), "brute");
        Map<String, Object> r = in.residual() == null || in.residual().isEmpty() ? null
                : RiskScales.rate(scale, in.residual(), "résiduelle");
        jdbc.sql("""
                update risk set type = :type, site_id = :site, work_unit = :wu, process = :proc, hazard = :haz,
                  situation = :sit, existing_measures = :mes, gross = cast(:g as jsonb), gross_score = :gs,
                  gross_level = :gl, residual = cast(:r as jsonb), residual_score = :rsc, residual_level = :rl,
                  reassessment_date = :rd, updated_at = now() where id = :id""")
                .param("type", in.type()).param("site", in.siteId()).param("wu", blank(in.workUnit()))
                .param("proc", blank(in.process())).param("haz", in.hazard().strip())
                .param("sit", blank(in.situation())).param("mes", blank(in.existingMeasures()))
                .param("g", write(g.get("ratings"))).param("gs", g.get("score")).param("gl", g.get("level"))
                .param("r", r == null ? null : write(r.get("ratings"))).param("rsc", r == null ? null : r.get("score"))
                .param("rl", r == null ? null : r.get("level")).param("rd", in.reassessmentDate()).param("id", id)
                .update();
        if (!Objects.equals(rating(before.get("gross")), g.get("ratings"))) {
            history(id, "gross", g);
        }
        if (r != null && !Objects.equals(rating(before.get("residual")), r.get("ratings"))) {
            history(id, "residual", r);
        }
        if (in.requirementIds() != null) {
            jdbc.sql("delete from risk_requirement where risk_id = :id").param("id", id).update();
            links(id, in.requirementIds());
        }
        audit.record("RISK_UPDATED", "risk", id.toString(), null);
        return get(id);
    }

    /** Action de maîtrise : fiche ACTION M1 ({@code data.origine = "risque"}) rattachée au risque. */
    Map<String, Object> addAction(UUID id, ActionInput in) {
        user.requireWriter();
        Map<String, Object> risk = one(id);
        @SuppressWarnings("unchecked")
        UUID site = (UUID) ((Map<String, Object>) risk.get("site")).get("id");
        UUID action = actions.create(in.title(), in.description(), site, in.ownerId(), in.dueDate(),
                in.actionType() == null ? "maitrise" : in.actionType(),
                Map.of("origine", "risque", "riskId", id, "riskReference", risk.get("reference")));
        jdbc.sql("insert into risk_action (tenant_id, risk_id, action_id) values (app_current_tenant(), :r, :a)")
                .param("r", id).param("a", action).update();
        audit.record("RISK_ACTION_ADDED", "risk", id.toString(), write(Map.of("actionId", action)));
        return get(id);
    }

    /** Cases de la matrice (2 premières dimensions de la formule) avec nombre et liste ; base résiduelle par défaut. */
    @Transactional(readOnly = true)
    Map<String, Object> matrix(UUID siteId, String type, String basis, String scaleCode) {
        Scale scale = scales.byCode(scaleCode);
        boolean gross = "gross".equals(basis);
        List<Dimension> f = scale.formula();
        Dimension rows = f.get(0);
        Dimension cols = f.get(1);
        Map<String, List<Map<String, Object>>> byCell = new HashMap<>();
        for (Map<String, Object> r : list(siteId, type, null)) {
            if (!scale.id().equals(r.get("scaleId"))) {
                continue;
            }
            Object rated = gross || r.get("residual") == null ? r.get("gross") : r.get("residual");
            Map<String, Integer> ratings = rating(rated);
            String key = ratings.get(rows.code()) + ":" + ratings.get(cols.code());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("reference", r.get("reference"));
            item.put("hazard", r.get("hazard"));
            item.put("type", r.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> rr = (Map<String, Object>) rated;
            item.put("score", rr.get("score"));
            item.put("level", rr.get("level"));
            byCell.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int y = rows.levels().size(); y >= 1; y--) {
            for (int x = 1; x <= cols.levels().size(); x++) {
                int score = x * y;
                var t = scale.threshold(score);
                List<Map<String, Object>> items = byCell.getOrDefault(y + ":" + x, List.of());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("row", y);
                c.put("col", x);
                c.put("score", score);
                c.put("level", t.level());
                c.put("color", t.color());
                c.put("count", items.size());
                c.put("risks", items);
                cells.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scale", Map.of("id", scale.id(), "code", scale.code(), "label", scale.label()));
        out.put("basis", gross ? "gross" : "residual");
        out.put("rows", rows);
        out.put("columns", cols);
        out.put("thresholds", scale.thresholds());
        out.put("cells", cells);
        return out;
    }

    ImportReport importFile(byte[] content, boolean dryRun) {
        user.requireManager();
        TabularFile.Table t = TabularFile.read(content, IMPORT_COLUMNS, List.of("type", "site", "danger",
                "cotation_brute"));
        Map<String, UUID> sites = new HashMap<>();
        jdbc.sql("select code, id from site").query((rs, i) -> sites.put(rs.getString(1), rs.getObject(2, UUID.class)))
                .list();
        List<LineError> errors = new ArrayList<>();
        List<RiskInput> items = new ArrayList<>();
        for (int r = 0; r < t.rows().size(); r++) {
            List<String> row = t.rows().get(r);
            int line = r + 2;
            try {
                String site = t.cell(row, "site");
                if (!sites.containsKey(site)) {
                    throw ApiException.badRequest("site « " + site + " » inconnu");
                }
                String rd = t.cell(row, "reevaluation");
                RiskInput in = new RiskInput(t.cell(row, "type").toLowerCase(java.util.Locale.ROOT), sites.get(site),
                        blank(t.cell(row, "echelle")), t.cell(row, "unite_travail"), t.cell(row, "processus"),
                        t.cell(row, "danger"), t.cell(row, "situation"), t.cell(row, "mesures"),
                        parseRatings(t.cell(row, "cotation_brute")), parseRatings(t.cell(row, "cotation_residuelle")),
                        rd.isEmpty() ? null : LocalDate.parse(rd), null);
                validate(in);
                Scale s = scales.byCode(in.scaleCode());
                RiskScales.rate(s, in.gross(), "brute");
                if (!in.residual().isEmpty()) {
                    RiskScales.rate(s, in.residual(), "résiduelle");
                }
                items.add(in);
            } catch (ApiException e) {
                errors.add(new LineError(line, e.getMessage()));
            } catch (RuntimeException e) {
                errors.add(new LineError(line, "valeur illisible (cotation « gravite=3,probabilite=2 », date "
                        + "AAAA-MM-JJ)"));
            }
        }
        boolean applied = errors.isEmpty() && !dryRun;
        if (applied) {
            items.forEach(this::create);
            audit.record("RISKS_IMPORTED", "risk", null, write(Map.of("created", items.size())));
        }
        return new ImportReport(errors.isEmpty() ? items.size() : items.size(), 0, List.copyOf(errors), applied,
                dryRun);
    }

    /** {@code gravite=3,probabilite=2} (séparateur , ou |). */
    static Map<String, Integer> parseRatings(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) {
            return m;
        }
        for (String part : s.split("[,|]")) {
            String[] kv = part.split("=");
            m.put(kv[0].strip(), Integer.valueOf(kv[1].strip()));
        }
        return m;
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> duerRisks(UUID siteId) {
        return list(siteId, "sst", null);
    }

    // ------------------------------------------------------------------ interne

    private void validate(RiskInput in) {
        if (in.type() == null || !TYPES.contains(in.type())) {
            throw ApiException.badRequest("type : sst, environnement, qualite ou opportunite");
        }
        if (in.siteId() == null || jdbc.sql("select count(*) from site where id = :id").param("id", in.siteId())
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Site inconnu");
        }
        if (in.hazard() == null || in.hazard().isBlank() || in.hazard().length() > 500) {
            throw ApiException.badRequest("Danger ou aspect obligatoire (500 caractères maximum)");
        }
        if (len(in.workUnit()) > 200 || len(in.process()) > 200 || len(in.situation()) > 2000
                || len(in.existingMeasures()) > 4000) {
            throw ApiException.badRequest("Texte trop long (unité, processus 200 ; situation 2000 ; mesures 4000)");
        }
    }

    private void links(UUID id, List<UUID> requirementIds) {
        if (requirementIds == null) {
            return;
        }
        for (UUID req : requirementIds.stream().distinct().toList()) {
            if (jdbc.sql("select count(*) from requirement where id = :id").param("id", req).query(Long.class)
                    .single() == 0) {
                throw ApiException.badRequest("Exigence inconnue : " + req);
            }
            jdbc.sql("insert into risk_requirement (tenant_id, risk_id, requirement_id) "
                    + "values (app_current_tenant(), :r, :q)").param("r", id).param("q", req).update();
        }
    }

    private void history(UUID id, String kind, Map<String, Object> rated) {
        jdbc.sql("""
                insert into risk_rating_history (tenant_id, risk_id, kind, ratings, score, level, actor_id)
                values (app_current_tenant(), :id, :k, cast(:r as jsonb), :s, :l, app_current_user())""")
                .param("id", id).param("k", kind).param("r", write(rated.get("ratings"))).param("s", rated.get("score"))
                .param("l", rated.get("level")).update();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> rating(Object rated) {
        return rated == null ? null : (Map<String, Integer>) ((Map<String, Object>) rated).get("ratings");
    }

    private Map<String, Object> view(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID scaleId = rs.getObject("scale_id", UUID.class);
        Scale scale = scales.byId(scaleId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("reference", rs.getString("reference"));
        m.put("type", rs.getString("type"));
        m.put("site", Map.of("id", rs.getObject("site_id"), "code", rs.getString("s_code"),
                "name", rs.getString("s_name")));
        m.put("scaleId", scaleId);
        m.put("scaleCode", rs.getString("scale_code"));
        m.put("workUnit", rs.getString("work_unit"));
        m.put("process", rs.getString("process"));
        m.put("hazard", rs.getString("hazard"));
        m.put("situation", rs.getString("situation"));
        m.put("existingMeasures", rs.getString("existing_measures"));
        m.put("gross", RiskScales.rate(scale, toInts(readMap(rs.getString("gross"))), "brute"));
        String residual = rs.getString("residual");
        m.put("residual", residual == null ? null : RiskScales.rate(scale, toInts(readMap(residual)), "résiduelle"));
        m.put("reassessmentDate", rs.getObject("reassessment_date", LocalDate.class));
        List<UUID> actionIds = jdbc.sql("select action_id from risk_action where risk_id = :id").param("id", id)
                .query(UUID.class).list();
        m.put("actions", actions.refs(actionIds));
        m.put("requirements", jdbc.sql("""
                select q.id, q.code, q.title, s.code s_code from risk_requirement rr
                join requirement q on q.id = rr.requirement_id join standard s on s.id = q.standard_id
                where rr.risk_id = :id order by s.code, q.code""").param("id", id)
                .query((r2, i) -> Map.of("id", r2.getObject(1), "code", r2.getString(2), "title", r2.getString(3),
                        "standardCode", r2.getString(4))).list());
        m.put("createdAt", rs.getTimestamp("created_at").toInstant());
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
        return m;
    }

    private static Map<String, Integer> toInts(Map<String, Object> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, ((Number) v).intValue()));
        return out;
    }

    private Map<String, Object> readMap(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
