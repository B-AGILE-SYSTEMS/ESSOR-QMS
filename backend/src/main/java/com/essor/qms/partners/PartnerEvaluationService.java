package com.essor.qms.partners;

import com.essor.qms.partners.EvaluationGrids.Band;
import com.essor.qms.partners.EvaluationGrids.Criterion;
import com.essor.qms.partners.EvaluationGrids.GradeClass;
import com.essor.qms.partners.EvaluationGrids.Grid;
import com.essor.qms.records.Actions;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Évaluations clients et fournisseurs : notes par critère + indicateurs automatiques calculés sur la période,
 * note globale (0-100) = moyenne pondérée des notes / scaleMax, classe ; classe de {@code improvementClasses} →
 * plan de progrès (fiche ACTION M1). Campagnes : création des évaluations « à faire ».
 * <p>Indicateurs fournisseur : NC liées ({@code data.supplierId}), lots reçus (créés sur la période), % refusés et
 * nombre en dérogation (passés au statut sur la période), retards ({@code expected_date} dépassée à la réception).
 * Client : réclamations (NC dont {@code data.clientId} = le client).
 */
@Service
@Transactional
class PartnerEvaluationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final int PLAN_DUE_DAYS = 60;
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-T([1-4])$");
    private static final Pattern SEMESTER = Pattern.compile("^(\\d{4})-S([12])$");
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])$");
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");

    record Period(String code, LocalDate start, LocalDate end) {
    }

    record EvaluationInput(UUID evaluationId, UUID gridId, String grid, String period, Map<String, Double> scores,
                           String comment) {
    }

    record CampaignInput(String grid, List<UUID> partnerIds, String period) {
    }

    private final JdbcClient jdbc;
    private final EvaluationGrids grids;
    private final CurrentUser user;
    private final AuditLog audit;
    private final Actions actions;
    private final ObjectMapper json;

    PartnerEvaluationService(JdbcClient jdbc, EvaluationGrids grids, CurrentUser user, AuditLog audit,
                             Actions actions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.grids = grids;
        this.user = user;
        this.audit = audit;
        this.actions = actions;
        this.json = json;
    }

    /** {@code 2026-T3} (trimestre), {@code 2026-S1}, {@code 2026-09} ou {@code 2026}. */
    static Period period(String code) {
        String c = code == null ? "" : code.strip();
        Matcher m;
        if ((m = QUARTER.matcher(c)).matches()) {
            LocalDate s = LocalDate.of(Integer.parseInt(m.group(1)), (Integer.parseInt(m.group(2)) - 1) * 3 + 1, 1);
            return new Period(c, s, s.plusMonths(3).minusDays(1));
        }
        if ((m = SEMESTER.matcher(c)).matches()) {
            LocalDate s = LocalDate.of(Integer.parseInt(m.group(1)), (Integer.parseInt(m.group(2)) - 1) * 6 + 1, 1);
            return new Period(c, s, s.plusMonths(6).minusDays(1));
        }
        if ((m = MONTH.matcher(c)).matches()) {
            LocalDate s = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
            return new Period(c, s, s.plusMonths(1).minusDays(1));
        }
        if ((m = YEAR.matcher(c)).matches()) {
            LocalDate s = LocalDate.of(Integer.parseInt(m.group(1)), 1, 1);
            return new Period(c, s, s.plusYears(1).minusDays(1));
        }
        throw ApiException.badRequest("Période : 2026-T3, 2026-S1, 2026-09 ou 2026");
    }

    // ------------------------------------------------------------------ indicateurs

    Map<String, Object> indicators(Map<String, Object> partner, Period p) {
        String pid = partner.get("id").toString();
        Map<String, Object> m = new LinkedHashMap<>();
        if ("fournisseur".equals(partner.get("type"))) {
            long nc = count("""
                    select count(*) from record where type_code = 'NC' and data->>'supplierId' = :p
                      and created_at >= :s and created_at < :e""", pid, p);
            long lots = count("""
                    select count(*) from lot where supplier_id = cast(:p as uuid)
                      and created_at >= :s and created_at < :e""", pid, p);
            long refused = count(statusSql("REFUSE"), pid, p);
            long derog = count(statusSql("DEROGATION"), pid, p);
            long late = count("""
                    select count(*) from lot where supplier_id = cast(:p as uuid) and created_at >= :s
                      and created_at < :e and expected_date is not null
                      and cast(created_at at time zone 'Africa/Casablanca' as date) > expected_date""", pid, p);
            m.put("ncCount", nc);
            m.put("lotsReceived", lots);
            m.put("lotsRefused", refused);
            m.put("lotsRefusedPct", lots == 0 ? 0.0 : BigDecimal.valueOf(refused * 100.0)
                    .divide(BigDecimal.valueOf(lots), 1, RoundingMode.HALF_UP).doubleValue());
            m.put("lotsDerogation", derog);
            m.put("lateDeliveries", late);
        } else {
            m.put("complaints", count("""
                    select count(*) from record where type_code = 'NC' and data->>'clientId' = :p
                      and created_at >= :s and created_at < :e""", pid, p));
        }
        return m;
    }

    private static String statusSql(String status) {
        return """
                select count(distinct l.id) from lot l join lot_status_history h on h.lot_id = l.id
                where l.supplier_id = cast(:p as uuid) and h.to_status = '%s' and h.at >= :s and h.at < :e"""
                .formatted(status);
    }

    private long count(String sql, String partnerId, Period p) {
        return jdbc.sql(sql).param("p", partnerId).param("s", p.start().atStartOfDay(ZONE).toOffsetDateTime())
                .param("e", p.end().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()).query(Long.class).single();
    }

    static Double indicatorValue(String auto, Map<String, Object> ind) {
        Object v = switch (auto) {
            case "nc_count" -> ind.get("ncCount");
            case "lots_refused_pct" -> ind.get("lotsRefusedPct");
            case "lots_derogation" -> ind.get("lotsDerogation");
            case "late_deliveries" -> ind.get("lateDeliveries");
            default -> ind.get("complaints");
        };
        return v == null ? null : ((Number) v).doubleValue();
    }

    static int bandScore(List<Band> bands, double value) {
        for (Band b : bands) {
            if (b.max() == null || value <= b.max()) {
                return b.score();
            }
        }
        return bands.get(bands.size() - 1).score();
    }

    // ------------------------------------------------------------------ évaluations

    Map<String, Object> evaluate(UUID partnerId, EvaluationInput in) {
        user.requireWriter();
        Map<String, Object> partner = partner(partnerId);
        String gridRef = in.gridId() != null ? in.gridId().toString() : in.grid();
        String periodCode = in.period();
        if (in.evaluationId() != null) {
            Map<String, Object> todo = jdbc.sql("""
                    select cast(grid_id as text) g, period from partner_evaluation where id = :id and partner_id = :p""")
                    .param("id", in.evaluationId()).param("p", partnerId)
                    .query((rs, i) -> Map.<String, Object>of("g", rs.getString(1), "p", rs.getString(2))).optional()
                    .orElseThrow(() -> ApiException.notFound("Évaluation"));
            gridRef = (String) todo.get("g");
            periodCode = (String) todo.get("p");
        }
        Grid grid = grids.find(gridRef);
        if (!grid.partnerType().equals(partner.get("type"))) {
            throw ApiException.badRequest("Grille " + grid.code() + " réservée aux partenaires de type "
                    + grid.partnerType());
        }
        if (in.comment() != null && in.comment().length() > 2000) {
            throw ApiException.badRequest("Commentaire : 2000 caractères maximum");
        }
        Period p = period(periodCode);
        Map<String, Object> ind = indicators(partner, p);
        Map<String, Object> scores = new LinkedHashMap<>();
        double sum = 0;
        double weights = 0;
        for (Criterion c : grid.criteria()) {
            double score;
            if (c.auto() != null) {
                Double v = indicatorValue(c.auto(), ind);
                if (v == null) {
                    continue;
                }
                score = bandScore(c.bands(), v);
            } else {
                Double s = in.scores() == null ? null : in.scores().get(c.code());
                if (s == null || s < 0 || s > grid.scaleMax()) {
                    throw ApiException.badRequest("Note du critère " + c.code() + " entre 0 et " + grid.scaleMax()
                            + " obligatoire");
                }
                score = s;
            }
            scores.put(c.code(), score);
            sum += c.weight() * score / grid.scaleMax();
            weights += c.weight();
        }
        double global = BigDecimal.valueOf(weights == 0 ? 0 : sum * 100 / weights).setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
        GradeClass cls = grid.classes().stream().sorted(Comparator.comparingDouble(GradeClass::min).reversed())
                .filter(c -> global >= c.min()).findFirst()
                .orElse(grid.classes().get(grid.classes().size() - 1));
        UUID existing = jdbc.sql("""
                select id from partner_evaluation where partner_id = :p and grid_id = :g and period = :per""")
                .param("p", partnerId).param("g", grid.id()).param("per", p.code()).query(UUID.class).optional()
                .orElse(null);
        if (existing != null && jdbc.sql("select status from partner_evaluation where id = :id").param("id", existing)
                .query(String.class).single().equals("terminee")) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "evaluation_done",
                    "Ce partenaire est déjà évalué sur cette grille et cette période");
        }
        UUID action = null;
        if (grid.improvementClasses().contains(cls.code())) {
            action = actions.create("Plan de progrès " + partner.get("code") + " (" + p.code() + ", classe "
                            + cls.code() + ")", "Évaluation " + grid.label() + " : note " + global + " / 100.",
                    null, null, LocalDate.now(ZONE).plusDays(PLAN_DUE_DAYS), "progres",
                    Map.of("origine", "evaluation_partenaire", "partnerId", partnerId,
                            "partnerCode", partner.get("code"), "period", p.code()));
        }
        UUID id = jdbc.sql("""
                insert into partner_evaluation (tenant_id, partner_id, grid_id, period, period_start, period_end,
                  status, scores, indicators, global_score, class, comment, action_id, evaluated_by, evaluated_at)
                values (app_current_tenant(), :p, :g, :per, :s, :e, 'terminee', cast(:sc as jsonb),
                  cast(:ind as jsonb), :glob, :cls, :com, :act, app_current_user(), now())
                on conflict (tenant_id, partner_id, grid_id, period) do update set status = 'terminee',
                  scores = excluded.scores, indicators = excluded.indicators, global_score = excluded.global_score,
                  class = excluded.class, comment = excluded.comment, action_id = excluded.action_id,
                  evaluated_by = excluded.evaluated_by, evaluated_at = excluded.evaluated_at
                returning id""")
                .param("p", partnerId).param("g", grid.id()).param("per", p.code()).param("s", p.start())
                .param("e", p.end()).param("sc", write(scores)).param("ind", write(ind)).param("glob", global)
                .param("cls", cls.code()).param("com", in.comment() == null || in.comment().isBlank() ? null
                        : in.comment().strip()).param("act", action).query(UUID.class).single();
        audit.record("PARTNER_EVALUATED", "partner", partnerId.toString(),
                write(Map.of("evaluationId", id, "class", cls.code())));
        return list("e.id = :id", Map.of("id", id)).get(0);
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> evaluations(UUID partnerId) {
        partner(partnerId);
        return list("e.partner_id = :p", Map.of("p", partnerId));
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> todo(String status) {
        return list("(:st = '' or e.status = :st)", Map.of("st", status == null ? "" : status));
    }

    /** Historique chronologique des notes et indicateurs, et indicateurs de la période en cours. */
    @Transactional(readOnly = true)
    Map<String, Object> scorecard(UUID partnerId) {
        Map<String, Object> partner = partner(partnerId);
        List<Map<String, Object>> done = new ArrayList<>(list("e.partner_id = :p and e.status = 'terminee'",
                Map.of("p", partnerId)));
        done.sort(Comparator.comparing(m -> (LocalDate) m.get("periodStart")));
        LocalDate today = LocalDate.now(ZONE);
        Period current = period(today.getYear() + "-T" + ((today.getMonthValue() - 1) / 3 + 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partner", partner);
        out.put("history", done.stream().map(e -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("evaluationId", e.get("id"));
            h.put("grid", e.get("grid"));
            h.put("period", e.get("period"));
            h.put("globalScore", e.get("globalScore"));
            h.put("class", e.get("class"));
            h.put("indicators", e.get("indicators"));
            return h;
        }).toList());
        out.put("latest", done.isEmpty() ? null : done.get(done.size() - 1));
        out.put("currentPeriod", Map.of("period", current.code(), "indicators", indicators(partner, current)));
        return out;
    }

    Map<String, Object> campaign(CampaignInput in) {
        user.requireManager();
        Grid grid = grids.find(in.grid());
        Period p = period(in.period());
        if (in.partnerIds() == null || in.partnerIds().isEmpty() || in.partnerIds().size() > 500) {
            throw ApiException.badRequest("1 à 500 partenaires (partnerIds)");
        }
        for (UUID pid : in.partnerIds()) {
            if (!grid.partnerType().equals(partner(pid).get("type"))) {
                throw ApiException.badRequest("Partenaire " + pid + " : type incompatible avec la grille");
            }
        }
        UUID id = jdbc.sql("""
                insert into evaluation_campaign (tenant_id, grid_id, period, period_start, period_end, created_by)
                values (app_current_tenant(), :g, :per, :s, :e, app_current_user()) returning id""")
                .param("g", grid.id()).param("per", p.code()).param("s", p.start()).param("e", p.end())
                .query(UUID.class).single();
        int created = 0;
        for (UUID pid : in.partnerIds().stream().distinct().toList()) {
            created += jdbc.sql("""
                    insert into partner_evaluation (tenant_id, partner_id, grid_id, campaign_id, period, period_start,
                      period_end, status)
                    values (app_current_tenant(), :p, :g, :c, :per, :s, :e, 'a_faire') on conflict do nothing""")
                    .param("p", pid).param("g", grid.id()).param("c", id).param("per", p.code())
                    .param("s", p.start()).param("e", p.end()).update();
        }
        audit.record("EVALUATION_CAMPAIGN_CREATED", "evaluation_campaign", id.toString(),
                write(Map.of("created", created)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("grid", Map.of("id", grid.id(), "code", grid.code(), "label", grid.label()));
        out.put("period", p.code());
        out.put("periodStart", p.start());
        out.put("periodEnd", p.end());
        out.put("created", created);
        out.put("evaluations", list("e.campaign_id = :c", Map.of("c", id)));
        return out;
    }

    // ------------------------------------------------------------------ outils

    private Map<String, Object> partner(UUID id) {
        return jdbc.sql("select id, code, name, type from partner where id = :id").param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1));
                    m.put("code", rs.getString(2));
                    m.put("name", rs.getString(3));
                    m.put("type", rs.getString(4));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("Partenaire"));
    }

    private List<Map<String, Object>> list(String where, Map<String, Object> params) {
        return jdbc.sql("""
                select e.*, p.code p_code, p.name p_name, p.type p_type, g.code g_code, g.label g_label,
                  coalesce(u.display_name, u.username) author, a.reference a_ref, a.status a_status
                from partner_evaluation e join partner p on p.id = e.partner_id
                join evaluation_grid g on g.id = e.grid_id left join app_user u on u.id = e.evaluated_by
                left join record a on a.id = e.action_id
                where\s""" + where + " order by e.period_start desc, p.code limit 1000").params(params)
                .query((rs, i) -> view(rs)).list();
    }

    private Map<String, Object> view(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id"));
        m.put("partner", Map.of("id", rs.getObject("partner_id"), "code", rs.getString("p_code"),
                "name", rs.getString("p_name"), "type", rs.getString("p_type")));
        m.put("grid", Map.of("id", rs.getObject("grid_id"), "code", rs.getString("g_code"),
                "label", rs.getString("g_label")));
        m.put("campaignId", rs.getObject("campaign_id"));
        m.put("period", rs.getString("period"));
        m.put("periodStart", rs.getObject("period_start", LocalDate.class));
        m.put("periodEnd", rs.getObject("period_end", LocalDate.class));
        m.put("status", rs.getString("status"));
        m.put("scores", rs.getString("scores") == null ? null : read(rs.getString("scores")));
        m.put("indicators", rs.getString("indicators") == null ? null : read(rs.getString("indicators")));
        BigDecimal g = rs.getBigDecimal("global_score");
        m.put("globalScore", g == null ? null : g.doubleValue());
        m.put("class", rs.getString("class"));
        m.put("comment", rs.getString("comment"));
        m.put("action", rs.getObject("action_id") == null ? null : Map.of("id", rs.getObject("action_id"),
                "reference", rs.getString("a_ref"), "status", rs.getString("a_status")));
        m.put("evaluatedBy", rs.getString("author"));
        m.put("evaluatedAt", rs.getTimestamp("evaluated_at") == null ? null
                : rs.getTimestamp("evaluated_at").toInstant());
        return m;
    }

    private Map<String, Object> read(String s) {
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
}
