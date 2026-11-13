package com.essor.qms.traceability;

import com.essor.qms.records.NonConformities;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.traceability.ControlPlanService.Characteristic;
import com.essor.qms.traceability.ControlPlanService.Plan;
import com.essor.qms.traceability.LotService.Lot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contrôles : création (taille d'échantillon calculée), résultats, décision (statut du lot, NC), SPC. */
@Service
@Transactional
class InspectionService {

    static final Set<String> DECISIONS = Set.of("LIBERE", "QUARANTAINE", "DEROGATION", "REFUSE", "DETRUIT");
    /** Rôles autorisés à décider (un FIELD_USER saisit les résultats sans décider). */
    static final String[] DECIDERS = {TraceSupport.QUALITY_MANAGER, "AUDITOR"};
    private static final int MAX_VALUES = 1000;

    record CreateInspection(UUID lotId, UUID controlPlanId, String point) {
    }

    record ResultInput(String characteristic, List<Object> values, Boolean ok, String instrument, UUID instrumentId) {
    }

    record Result(String characteristic, List<Object> values, boolean ok, String instrument, UUID instrumentId,
                  int planVersion,
                  UUID siteId, String author, Instant at) {
    }

    record Decision(String decision, String comment) {
    }

    /** {@code status} : A_FAIRE (aucun résultat), EN_COURS, TERMINEE (décision prise). */
    record Inspection(UUID id, Lot lot, Plan controlPlan, String point, Map<String, Object> sampling, int sampleSize,
                      String status, List<Result> results, String proposedDecision, String decision, String comment,
                      UUID ncId, String createdBy, Instant createdAt, String decidedBy, Instant decidedAt) {
    }

    private final JdbcClient jdbc;
    private final TraceSupport support;
    private final AuditLog audit;
    private final LotService lots;
    private final ControlPlanService plans;
    private final NonConformities ncs;
    private final ObjectMapper json;

    InspectionService(JdbcClient jdbc, TraceSupport support, AuditLog audit, LotService lots,
                      ControlPlanService plans, NonConformities ncs, ObjectMapper json) {
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
        this.lots = lots;
        this.plans = plans;
        this.ncs = ncs;
        this.json = json;
    }

    Inspection create(CreateInspection in) {
        support.requireWriter();
        if (in.lotId() == null || in.controlPlanId() == null) {
            throw ApiException.badRequest("lotId et controlPlanId obligatoires");
        }
        Lot lot = lots.lot(in.lotId());
        Plan plan = plans.get(in.controlPlanId());
        if (!plan.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "plan_outdated", "Version de plan non active");
        }
        String point = in.point() == null || in.point().isBlank() ? plan.point() : in.point();
        if (!ControlPlanService.POINTS.contains(point)) {
            throw ApiException.badRequest("Point de flux inconnu");
        }
        Map<String, Object> sampling = sampling(plan, lot);
        int size = (int) sampling.get("sampleSize");
        UUID id = jdbc.sql("""
                insert into inspection (tenant_id, lot_id, control_plan_id, point, site_id, sampling, sample_size,
                  created_by)
                values (app_current_tenant(), :lot, :plan, :point, :site, cast(:s as jsonb), :size, app_current_user())
                returning id""")
                .param("lot", lot.id()).param("plan", plan.id()).param("point", point)
                .param("site", lot.site() == null ? null : lot.site().id()).param("s", write(sampling))
                .param("size", size).query(UUID.class).single();
        audit.record("INSPECTION_CREATED", "inspection", id.toString(), write(Map.of("lot", lot.id())));
        return get(id);
    }

    static Map<String, Object> sampling(Plan plan, Lot lot) {
        Map<String, Object> s = new LinkedHashMap<>();
        int lotSize = lot.quantity().setScale(0, java.math.RoundingMode.CEILING).intValueExact();
        s.put("mode", plan.sampling().mode());
        s.put("lotSize", lotSize);
        if ("iso2859".equals(plan.sampling().mode())) {
            Iso2859.Plan p = Iso2859.plan(lotSize, plan.sampling().nqa());
            s.put("niveau", "II");
            s.put("nqa", p.nqa());
            s.put("codeLetter", p.letter());
            s.put("planLetter", p.planLetter());
            s.put("sampleSize", p.sampleSize());
            s.put("ac", p.ac());
            s.put("re", p.re());
        } else {
            s.put("sampleSize", Math.max(1, Math.min(plan.sampling().taille(), Math.max(lotSize, 1))));
        }
        return s;
    }

    @Transactional(readOnly = true)
    List<Inspection> list(String status, UUID lotId, String point) {
        List<UUID> ids = jdbc.sql("""
                select i.id from inspection i
                where (cast(:lot as uuid) is null or i.lot_id = cast(:lot as uuid))
                  and (:point = '' or i.point = :point)
                  and (:status = ''
                       or (:status = 'TERMINEE' and i.decision is not null)
                       or (:status = 'EN_COURS' and i.decision is null
                           and exists (select 1 from inspection_result r where r.inspection_id = i.id))
                       or (:status = 'A_FAIRE' and i.decision is null
                           and not exists (select 1 from inspection_result r where r.inspection_id = i.id)))
                order by i.created_at desc limit 200""")
                .param("lot", lotId).param("point", point == null ? "" : point)
                .param("status", status == null ? "" : status).query(UUID.class).list();
        return ids.stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    Inspection get(UUID id) {
        record Row(UUID lotId, UUID planId, String point, String sampling, int size, String decision, String comment,
                   UUID nc, String createdBy, Instant createdAt, String decidedBy, Instant decidedAt) {
        }
        Row r = jdbc.sql("""
                select i.*, cu.display_name cu_name, du.display_name du_name from inspection i
                left join app_user cu on cu.id = i.created_by left join app_user du on du.id = i.decided_by
                where i.id = :id""").param("id", id)
                .query((rs, i) -> new Row(rs.getObject("lot_id", UUID.class),
                        rs.getObject("control_plan_id", UUID.class), rs.getString("point"), rs.getString("sampling"),
                        rs.getInt("sample_size"), rs.getString("decision"), rs.getString("comment"),
                        rs.getObject("nc_id", UUID.class), rs.getString("cu_name"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("du_name"),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()))
                .optional().orElseThrow(() -> ApiException.notFound("Contrôle"));
        Plan plan = plans.get(r.planId());
        List<Result> results = results(id);
        String status = r.decision() != null ? "TERMINEE" : results.isEmpty() ? "A_FAIRE" : "EN_COURS";
        return new Inspection(id, lots.lot(r.lotId()), plan, r.point(), read(r.sampling()), r.size(), status, results,
                propose(plan, results), r.decision(), r.comment(), r.nc(), r.createdBy(), r.createdAt(),
                r.decidedBy(), r.decidedAt());
    }

    /**
     * Décision proposée : LIBERE si tous les résultats sont conformes et toutes les caractéristiques saisies ;
     * REFUSE si une caractéristique critique est non conforme ; QUARANTAINE sinon (écart ou saisie incomplète).
     */
    static String propose(Plan plan, List<Result> results) {
        if (results.isEmpty()) {
            return null;
        }
        Map<String, Result> byCode = new HashMap<>();
        results.forEach(r -> byCode.put(r.characteristic(), r));
        boolean critical = plan.characteristics().stream().anyMatch(c -> "critique".equals(c.criticite())
                && byCode.containsKey(c.code()) && !byCode.get(c.code()).ok());
        if (critical) {
            return "REFUSE";
        }
        boolean allOk = plan.characteristics().stream()
                .allMatch(c -> byCode.containsKey(c.code()) && byCode.get(c.code()).ok());
        return allOk ? "LIBERE" : "QUARANTAINE";
    }

    private List<Result> results(UUID id) {
        return jdbc.sql("""
                select r.characteristic, r.values_json, r.ok, r.instrument, r.plan_version, r.site_id, u.display_name,
                  r.at, r.instrument_id
                from inspection_result r left join app_user u on u.id = r.author_id
                where r.inspection_id = :id order by r.at, r.characteristic""").param("id", id)
                .query((rs, i) -> new Result(rs.getString(1), readList(rs.getString(2)), rs.getBoolean(3),
                        rs.getString(4), rs.getObject(9, UUID.class), rs.getInt(5), rs.getObject(6, UUID.class), rs.getString(7),
                        rs.getTimestamp(8).toInstant())).list();
    }

    /** Enregistre (ou remplace) les résultats. Mesures : {@code ok} toujours recalculé ; attributs : {@code ok} requis. */
    Inspection saveResults(UUID id, List<ResultInput> input) {
        support.requireWriter();
        Inspection insp = get(id);
        if (insp.decision() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "inspection_closed", "Contrôle déjà décidé");
        }
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("Au moins un résultat attendu");
        }
        Map<String, Characteristic> chars = new HashMap<>();
        insp.controlPlan().characteristics().forEach(c -> chars.put(c.code(), c));
        for (ResultInput in : input) {
            Characteristic c = chars.get(in.characteristic());
            if (c == null) {
                throw ApiException.badRequest("Caractéristique inconnue dans le plan : " + in.characteristic());
            }
            List<Object> values = in.values() == null ? List.of() : in.values();
            if (values.size() > MAX_VALUES) {
                throw ApiException.badRequest("Trop de valeurs (" + MAX_VALUES + " maximum)");
            }
            Boolean ok = in.ok();
            if ("mesure".equals(c.type())) {
                ok = conform(c, numbers(c.code(), values)); // verdict du client ignoré
            } else if (ok == null) {
                throw ApiException.badRequest("Caractéristique " + c.code() + " : verdict ok obligatoire");
            }
            String instrument = LotService.blankToNull(in.instrument());
            if (instrument != null && instrument.length() > 120) {
                throw ApiException.badRequest("Instrument : 120 caractères maximum");
            }
            if (in.instrumentId() != null) {
                instrument = usableInstrument(in.instrumentId());
            }
            jdbc.sql("""
                    insert into inspection_result (tenant_id, inspection_id, characteristic, values_json, ok, instrument,
                      instrument_id, plan_version, site_id, author_id)
                    values (app_current_tenant(), :id, :c, cast(:v as jsonb), :ok, :instr, :instrId, :ver, :site,
                      app_current_user())
                    on conflict (tenant_id, inspection_id, characteristic) do update set values_json = excluded.values_json,
                      ok = excluded.ok, instrument = excluded.instrument, instrument_id = excluded.instrument_id, author_id = excluded.author_id, at = now()""")
                    .param("id", id).param("c", c.code()).param("v", write(values)).param("ok", ok)
                    .param("instr", instrument).param("instrId", in.instrumentId()).param("ver", insp.controlPlan().version())
                    .param("site", insp.lot().site() == null ? null : insp.lot().site().id()).update();
        }
        audit.record("INSPECTION_RESULTS_SAVED", "inspection", id.toString(),
                write(Map.of("count", input.size())));
        return get(id);
    }

    /**
     * Instrument choisi dans un résultat (M4) : il doit exister, être en service et non échu, sinon 409
     * {@code instrument_unavailable}. Rend son code, recopié dans le champ libre {@code instrument}.
     */
    private String usableInstrument(UUID instrumentId) {
        Map<String, Object> i = jdbc.sql("select code, status, next_due from instrument where id = :id")
                .param("id", instrumentId)
                .query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("code", rs.getString(1));
                    m.put("status", rs.getString(2));
                    m.put("due", rs.getObject(3, java.time.LocalDate.class));
                    return m;
                }).optional().orElseThrow(() -> ApiException.badRequest("Instrument inconnu"));
        java.time.LocalDate due = (java.time.LocalDate) i.get("due");
        boolean overdue = due != null
                && due.isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Casablanca")));
        if (!"en_service".equals(i.get("status")) || overdue) {
            throw new ApiException(HttpStatus.CONFLICT, "instrument_unavailable",
                    "Instrument " + i.get("code") + (overdue ? " : étalonnage échu" : " : statut " + i.get("status"))
                            + ", il ne peut pas être utilisé",
                    Map.of("instrumentId", instrumentId, "status", i.get("status"), "overdue", overdue));
        }
        return (String) i.get("code");
    }

    /** Verdict d'une mesure : tolMin et tolMax sont des bornes ABSOLUES (pas des écarts au nominal). */
    static boolean conform(Characteristic c, List<Double> numbers) {
        return !numbers.isEmpty() && numbers.stream().allMatch(v -> (c.tolMin() == null || v >= c.tolMin())
                && (c.tolMax() == null || v <= c.tolMax()));
    }

    Inspection decide(UUID id, Decision in) {
        support.requireAny(DECIDERS);
        String decision = in.decision() == null ? "" : in.decision();
        if (!DECISIONS.contains(decision)) {
            throw ApiException.badRequest("Décision : LIBERE, QUARANTAINE, DEROGATION, REFUSE ou DETRUIT");
        }
        if ("DEROGATION".equals(decision)) {
            support.requireAny(TraceSupport.QUALITY_MANAGER);
        }
        Inspection insp = get(id);
        if (insp.decision() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "inspection_closed", "Contrôle déjà décidé");
        }
        String comment = LotService.blankToNull(in.comment());
        if (!"LIBERE".equals(decision) && comment == null) {
            throw ApiException.badRequest("Commentaire obligatoire pour la décision " + decision);
        }
        UUID nc = null;
        if ("REFUSE".equals(decision) || "DEROGATION".equals(decision)) {
            nc = createNc(insp, decision, comment);
        }
        jdbc.sql("""
                update inspection set decision = :d, comment = :c, nc_id = :nc, decided_by = app_current_user(),
                  decided_at = now() where id = :id""")
                .param("d", decision).param("c", comment).param("nc", nc).param("id", id).update();
        String reason = "Contrôle " + insp.controlPlan().code() + " v" + insp.controlPlan().version()
                + (comment == null ? "" : " : " + comment);
        lots.applyStatus(insp.lot().id(), decision, reason, nc, id);
        audit.record("INSPECTION_DECIDED", "inspection", id.toString(), write(Map.of("decision", decision)));
        return get(id);
    }

    private UUID createNc(Inspection insp, String decision, String comment) {
        Lot lot = insp.lot();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("origine", "controle");
        data.put("lotId", lot.id().toString());
        data.put("lotNumero", lot.numero());
        data.put("articleCode", lot.article().code());
        data.put("inspectionId", insp.id().toString());
        data.put("decision", decision);
        if (lot.supplier() != null) {
            data.put("supplierId", lot.supplier().id().toString());
            data.put("supplierCode", lot.supplier().code());
            data.put("supplierLot", lot.supplierLot());
        }
        StringBuilder d = new StringBuilder(comment == null ? "" : comment + "\n\n");
        for (Result r : insp.results()) {
            if (!r.ok()) {
                d.append("Non conforme : ").append(r.characteristic()).append(' ').append(r.values()).append('\n');
            }
        }
        String title = ("DEROGATION".equals(decision) ? "Dérogation" : "Refus") + " lot " + lot.numero() + " ("
                + lot.article().code() + ")" + (lot.supplier() == null ? "" : " — " + lot.supplier().name());
        String severity = "REFUSE".equals(decision) ? "majeure" : "mineure";
        return ncs.create(title, d.toString(), severity, lot.site() == null ? null : lot.site().id(), data,
                Map.of("source", "inspection", "inspectionId", insp.id().toString()));
    }

    // ------------------------------------------------------------------ SPC

    @Transactional(readOnly = true)
    Map<String, Object> spc(UUID planId, String characteristic, int limit) {
        Plan plan = plans.get(planId);
        Characteristic c = plan.characteristics().stream().filter(x -> x.code().equals(characteristic)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Caractéristique inconnue dans le plan"));
        if (!"mesure".equals(c.type())) {
            throw ApiException.badRequest("Carte de contrôle : caractéristique de type mesure attendue");
        }
        record Sub(UUID inspectionId, UUID lotId, String lotNumero, Instant at, List<Double> values) {
        }
        List<Sub> subs = new ArrayList<>(jdbc.sql("""
                select * from (
                  select i.id, i.lot_id, l.numero, r.at, r.values_json from inspection_result r
                  join inspection i on i.id = r.inspection_id join control_plan p on p.id = i.control_plan_id
                  join lot l on l.id = i.lot_id
                  where p.code = :code and r.characteristic = :c order by r.at desc limit :limit) x
                order by at""")
                .param("code", plan.code()).param("c", characteristic).param("limit", Math.max(2, Math.min(limit, 500)))
                .query((rs, i) -> new Sub(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), numbers(characteristic, readList(rs.getString(5)))))
                .list());
        Map<Integer, Long> sizes = new HashMap<>();
        subs.forEach(s -> sizes.merge(s.values().size(), 1L, Long::sum));
        int n = sizes.entrySet().stream().filter(e -> Spc.supported(e.getKey()))
                .max(Map.Entry.<Integer, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(0);
        List<Sub> kept = subs.stream().filter(s -> s.values().size() == n).toList();
        Spc.Result res = Spc.compute(kept.stream().map(Sub::values).toList(), c.tolMin(), c.tolMax());
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            Sub s = kept.get(i);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("inspectionId", s.inspectionId());
            p.put("lotId", s.lotId());
            p.put("lotNumero", s.lotNumero());
            p.put("at", s.at());
            p.put("values", s.values());
            p.put("mean", res.points().get(i).mean());
            p.put("range", res.points().get(i).range());
            points.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlPlanId", plan.id());
        out.put("characteristic", c);
        out.put("subgroupSize", res.subgroupSize());
        out.put("excludedSubgroups", subs.size() - kept.size());
        out.put("points", points);
        out.put("limits", res.xbar() == null ? null : Map.of("xbar", res.xbar(), "range", res.r()));
        out.put("sigma", res.sigma());
        out.put("cp", res.cp());
        out.put("cpk", res.cpk());
        out.put("alerts", res.alerts());
        return out;
    }

    private static List<Double> numbers(String code, List<Object> values) {
        List<Double> out = new ArrayList<>();
        for (Object v : values) {
            if (v instanceof Number num) {
                out.add(num.doubleValue());
            } else {
                try {
                    out.add(Double.parseDouble(String.valueOf(v).replace(',', '.').strip()));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Caractéristique " + code + " : valeur numérique attendue");
                }
            }
        }
        return out;
    }

    private Map<String, Object> read(String s) {
        try {
            return json.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Object> readList(String s) {
        try {
            return json.readValue(s, new TypeReference<List<Object>>() { });
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
