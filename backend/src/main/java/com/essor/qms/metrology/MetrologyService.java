package com.essor.qms.metrology;

import com.essor.qms.records.NonConformities;
import com.essor.qms.records.RecordEvents.Alert;
import com.essor.qms.records.RecordEvents.AlertKind;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.essor.qms.shared.ExecutionContext;
import com.essor.qms.shared.TabularFile;
import com.essor.qms.shared.TabularFile.ImportReport;
import com.essor.qms.shared.TabularFile.LineError;
import com.essor.qms.traceability.Lots;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parc d'instruments, étalonnages (verdict calculé ici avec l'EMT), blocage + NC + notification, effet instrument
 * (contrôles et lots mesurés depuis le dernier étalonnage conforme), mise en quarantaine via l'API lots M3b.
 */
@Service
@Transactional
class MetrologyService {

    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final int DUE_SOON_DAYS = 15;
    static final Set<String> STATUSES = Set.of("en_service", "bloque", "en_etalonnage", "reforme");
    static final List<String> IMPORT_COLUMNS = List.of("code", "designation", "type", "site", "emplacement",
            "numero_serie", "plage_min", "plage_max", "unite", "resolution", "emt", "periodicite_mois", "prestataire",
            "prochaine_echeance");
    private static final int MAX_POINTS = 100;

    record InstrumentInput(String code, String label, String type, UUID siteId, String location, String serialNumber,
                           BigDecimal rangeMin, BigDecimal rangeMax, String unit, BigDecimal resolution,
                           BigDecimal emt, Integer periodMonths, String provider, String status, LocalDate nextDue) {
    }

    record PointInput(BigDecimal nominal, BigDecimal mesure) {
    }

    record CalibrationInput(LocalDate calibratedOn, String provider, Boolean internal, String certificateNumber,
                            List<PointInput> points, String comment) {
    }

    record QuarantineInput(String reason, LocalDate since) {
    }

    static final String SELECT = """
            select i.*, s.code s_code, s.name s_name from instrument i left join site s on s.id = i.site_id
            """;

    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final AuditLog audit;
    private final NonConformities ncs;
    private final Lots lots;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    MetrologyService(JdbcClient jdbc, CurrentUser user, AuditLog audit, NonConformities ncs, Lots lots,
                     ApplicationEventPublisher events, ObjectMapper json) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
        this.ncs = ncs;
        this.lots = lots;
        this.events = events;
        this.json = json;
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ------------------------------------------------------------------ instruments

    @Transactional(readOnly = true)
    List<Map<String, Object>> list(String q, String status, UUID siteId, boolean selectable) {
        return jdbc.sql(SELECT + """
                where (:q = '' or i.code ilike '%' || :q || '%' or i.label ilike '%' || :q || '%'
                       or i.serial_number ilike '%' || :q || '%')
                  and (:status = '' or i.status = :status)
                  and (cast(:site as uuid) is null or i.site_id = cast(:site as uuid))
                  and (not :sel or (i.status = 'en_service' and (i.next_due is null or i.next_due >= :today)))
                order by i.code limit 500""")
                .param("q", q == null ? "" : q.strip()).param("status", status == null ? "" : status)
                .param("site", siteId).param("sel", selectable).param("today", today())
                .query((rs, n) -> view(rs)).list();
    }

    @Transactional(readOnly = true)
    Map<String, Object> instrument(UUID id) {
        return jdbc.sql(SELECT + " where i.id = :id").param("id", id).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Instrument"));
    }

    @Transactional(readOnly = true)
    Map<String, Object> detail(UUID id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", instrument(id));
        out.put("calibrations", calibrations(id));
        return out;
    }

    Map<String, Object> create(InstrumentInput in) {
        user.requireManager();
        List<String> errors = validate(in, true);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", errors));
        }
        if (jdbc.sql("select count(*) from instrument where code = :c").param("c", in.code().strip())
                .query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate", "Ce code instrument existe déjà");
        }
        UUID id = insert(in);
        audit.record("INSTRUMENT_CREATED", "instrument", id.toString(), null);
        return instrument(id);
    }

    Map<String, Object> update(UUID id, InstrumentInput in) {
        user.requireManager();
        instrument(id);
        List<String> errors = validate(in, false);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", errors));
        }
        jdbc.sql("""
                update instrument set label = :label, type = :type, site_id = :site, location = :loc,
                  serial_number = :sn, range_min = :rmin, range_max = :rmax, unit = :unit, resolution = :res,
                  emt = :emt, period_months = :per, provider = :prov, status = coalesce(:status, status),
                  next_due = coalesce(:due, next_due), updated_at = now()
                where id = :id""")
                .param("label", in.label().strip()).param("type", in.type()).param("site", in.siteId())
                .param("loc", in.location()).param("sn", in.serialNumber()).param("rmin", in.rangeMin())
                .param("rmax", in.rangeMax()).param("unit", in.unit()).param("res", in.resolution())
                .param("emt", in.emt()).param("per", in.periodMonths()).param("prov", in.provider())
                .param("status", in.status()).param("due", in.nextDue()).param("id", id).update();
        audit.record("INSTRUMENT_UPDATED", "instrument", id.toString(), null);
        return instrument(id);
    }

    private UUID insert(InstrumentInput in) {
        return jdbc.sql("""
                insert into instrument (tenant_id, code, label, type, site_id, location, serial_number, range_min,
                  range_max, unit, resolution, emt, period_months, provider, status, next_due)
                values (app_current_tenant(), :code, :label, :type, :site, :loc, :sn, :rmin, :rmax, :unit, :res,
                  :emt, :per, :prov, coalesce(:status, 'en_service'), :due)
                on conflict (tenant_id, code) do update set label = excluded.label, type = excluded.type,
                  site_id = excluded.site_id, location = excluded.location, serial_number = excluded.serial_number,
                  range_min = excluded.range_min, range_max = excluded.range_max, unit = excluded.unit,
                  resolution = excluded.resolution, emt = excluded.emt, period_months = excluded.period_months,
                  provider = excluded.provider, next_due = coalesce(excluded.next_due, instrument.next_due),
                  updated_at = now()
                returning id""")
                .param("code", in.code().strip()).param("label", in.label().strip()).param("type", in.type())
                .param("site", in.siteId()).param("loc", in.location()).param("sn", in.serialNumber())
                .param("rmin", in.rangeMin()).param("rmax", in.rangeMax()).param("unit", in.unit())
                .param("res", in.resolution()).param("emt", in.emt()).param("per", in.periodMonths())
                .param("prov", in.provider()).param("status", in.status()).param("due", in.nextDue())
                .query(UUID.class).single();
    }

    private List<String> validate(InstrumentInput in, boolean withCode) {
        List<String> e = new ArrayList<>();
        if (withCode && (in.code() == null || in.code().isBlank() || in.code().length() > 50)) {
            e.add("code obligatoire (50 caractères maximum)");
        }
        if (in.label() == null || in.label().isBlank() || in.label().length() > 200) {
            e.add("désignation obligatoire (200 caractères maximum)");
        }
        if (in.emt() == null || in.emt().signum() <= 0) {
            e.add("EMT strictement positive obligatoire");
        }
        if (in.periodMonths() == null || in.periodMonths() < 1 || in.periodMonths() > 120) {
            e.add("périodicité d'étalonnage (mois, 1 à 120) obligatoire");
        }
        if (in.status() != null && !STATUSES.contains(in.status())) {
            e.add("statut : en_service, bloque, en_etalonnage ou reforme");
        }
        if (in.siteId() != null && jdbc.sql("select count(*) from site where id = :id").param("id", in.siteId())
                .query(Long.class).single() == 0) {
            e.add("site inconnu");
        }
        if (in.rangeMin() != null && in.rangeMax() != null && in.rangeMin().compareTo(in.rangeMax()) > 0) {
            e.add("plage : minimum supérieur au maximum");
        }
        for (String s : new String[] {in.type(), in.unit(), in.serialNumber()}) {
            if (s != null && s.length() > 80) {
                e.add("type, unité, numéro de série : 80 caractères maximum");
            }
        }
        for (String s : new String[] {in.location(), in.provider()}) {
            if (s != null && s.length() > 200) {
                e.add("emplacement, prestataire : 200 caractères maximum");
            }
        }
        return e;
    }

    private Map<String, Object> view(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        UUID id = rs.getObject("id", UUID.class);
        UUID site = rs.getObject("site_id", UUID.class);
        LocalDate due = rs.getObject("next_due", LocalDate.class);
        String status = rs.getString("status");
        String dueState = due == null ? "aucune" : due.isBefore(today()) ? "echu"
                : due.isAfter(today().plusDays(DUE_SOON_DAYS)) ? "ok" : "bientot";
        m.put("id", id);
        m.put("code", rs.getString("code"));
        m.put("label", rs.getString("label"));
        m.put("type", rs.getString("type"));
        m.put("site", site == null ? null : Map.of("id", site, "code", rs.getString("s_code"),
                "name", rs.getString("s_name")));
        m.put("location", rs.getString("location"));
        m.put("serialNumber", rs.getString("serial_number"));
        m.put("rangeMin", rs.getBigDecimal("range_min"));
        m.put("rangeMax", rs.getBigDecimal("range_max"));
        m.put("unit", rs.getString("unit"));
        m.put("resolution", rs.getBigDecimal("resolution"));
        m.put("emt", rs.getBigDecimal("emt"));
        m.put("periodMonths", rs.getInt("period_months"));
        m.put("provider", rs.getString("provider"));
        m.put("status", status);
        m.put("nextDue", due);
        m.put("dueState", dueState);
        m.put("selectable", "en_service".equals(status) && !"echu".equals(dueState));
        m.put("qr", "essor://instrument/" + id);
        m.put("createdAt", rs.getTimestamp("created_at").toInstant());
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
        return m;
    }

    // ------------------------------------------------------------------ étalonnages

    @Transactional(readOnly = true)
    List<Map<String, Object>> calibrations(UUID instrumentId) {
        return jdbc.sql(CAL_SELECT + " where c.instrument_id = :id order by c.calibrated_on desc, c.created_at desc")
                .param("id", instrumentId).query((rs, n) -> calibrationView(rs)).list();
    }

    static final String CAL_SELECT = """
            select c.*, r.reference nc_reference, coalesce(u.display_name, u.username) author
            from calibration c left join record r on r.id = c.nc_id left join app_user u on u.id = c.created_by
            """;

    Map<String, Object> calibration(UUID id) {
        return jdbc.sql(CAL_SELECT + " where c.id = :id").param("id", id).query((rs, n) -> calibrationView(rs))
                .optional().orElseThrow(() -> ApiException.notFound("Étalonnage"));
    }

    /** Verdict serveur : conforme si |mesure − nominal| ≤ EMT pour chaque point. */
    Map<String, Object> addCalibration(UUID instrumentId, CalibrationInput in) {
        user.requireWriter();
        Map<String, Object> inst = instrument(instrumentId);
        if ("reforme".equals(inst.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "instrument_reforme", "Instrument réformé : étalonnage impossible");
        }
        if (in.calibratedOn() == null || in.calibratedOn().isAfter(today())) {
            throw ApiException.badRequest("Date d'étalonnage obligatoire, pas dans le futur");
        }
        if (in.points() == null || in.points().isEmpty() || in.points().size() > MAX_POINTS) {
            throw ApiException.badRequest("Points de mesure obligatoires (1 à " + MAX_POINTS + ")");
        }
        if (in.comment() != null && in.comment().length() > 2000) {
            throw ApiException.badRequest("Commentaire : 2000 caractères maximum");
        }
        BigDecimal emt = (BigDecimal) inst.get("emt");
        List<Map<String, Object>> points = new ArrayList<>();
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (PointInput p : in.points()) {
            if (p == null || p.nominal() == null || p.mesure() == null) {
                throw ApiException.badRequest("Chaque point exige nominal et mesure");
            }
            BigDecimal ecart = p.mesure().subtract(p.nominal());
            maxAbs = maxAbs.max(ecart.abs());
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("nominal", p.nominal());
            pt.put("mesure", p.mesure());
            pt.put("ecart", ecart);
            pt.put("conforme", ecart.abs().compareTo(emt) <= 0);
            points.add(pt);
        }
        boolean ok = maxAbs.compareTo(emt) <= 0;
        LocalDate nextDue = in.calibratedOn().plusMonths((Integer) inst.get("periodMonths"));
        UUID id = jdbc.sql("""
                insert into calibration (tenant_id, instrument_id, calibrated_on, provider, internal,
                  certificate_number, points, emt, max_abs_error, verdict, next_due, comment, created_by)
                values (app_current_tenant(), :i, :d, :prov, :internal, :cert, cast(:pts as jsonb), :emt, :max,
                  :verdict, :due, :comment, app_current_user()) returning id""")
                .param("i", instrumentId).param("d", in.calibratedOn())
                .param("prov", blank(in.provider()) == null ? inst.get("provider") : in.provider().strip())
                .param("internal", Boolean.TRUE.equals(in.internal())).param("cert", blank(in.certificateNumber()))
                .param("pts", write(points)).param("emt", emt).param("max", maxAbs)
                .param("verdict", ok ? "conforme" : "non_conforme").param("due", ok ? nextDue : null)
                .param("comment", blank(in.comment())).query(UUID.class).single();
        if (ok) {
            jdbc.sql("update instrument set status = 'en_service', next_due = :due, updated_at = now() where id = :id")
                    .param("due", nextDue).param("id", instrumentId).update();
        } else {
            block(inst, id, maxAbs, emt);
        }
        audit.record("CALIBRATION_RECORDED", "instrument", instrumentId.toString(),
                write(Map.of("calibrationId", id, "verdict", ok ? "conforme" : "non_conforme")));
        return calibration(id);
    }

    /** Non conforme : statut bloqué, NC liée, notification des responsables qualité. */
    private void block(Map<String, Object> inst, UUID calibrationId, BigDecimal maxAbs, BigDecimal emt) {
        UUID instrumentId = (UUID) inst.get("id");
        jdbc.sql("update instrument set status = 'bloque', updated_at = now() where id = :id")
                .param("id", instrumentId).update();
        @SuppressWarnings("unchecked")
        Map<String, Object> site = (Map<String, Object>) inst.get("site");
        UUID siteId = site == null ? null : (UUID) site.get("id");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("origine", "etalonnage");
        data.put("instrumentId", instrumentId);
        data.put("instrumentCode", inst.get("code"));
        data.put("calibrationId", calibrationId);
        data.put("ecartMax", maxAbs);
        data.put("emt", emt);
        UUID nc = ncs.create("Instrument " + inst.get("code") + " hors tolérance à l'étalonnage",
                "Écart maximal " + maxAbs.stripTrailingZeros().toPlainString() + " pour une EMT de "
                        + emt.stripTrailingZeros().toPlainString() + " " + (inst.get("unit") == null ? ""
                        : inst.get("unit")) + ". Instrument bloqué ; évaluer l'effet sur les contrôles et lots.",
                "majeure", siteId, data, Map.of("instrumentId", instrumentId));
        jdbc.sql("update calibration set nc_id = :nc where id = :id").param("nc", nc).param("id", calibrationId)
                .update();
        events.publishEvent(new Alert(AlertKind.INSTRUMENT_BLOCKED,
                "Instrument " + inst.get("code") + " bloqué : étalonnage non conforme",
                "/ressources/instruments/" + instrumentId, Set.of(), List.of(CurrentUser.QUALITY_MANAGER), null,
                ExecutionContext.userId()));
    }

    private Map<String, Object> calibrationView(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id"));
        m.put("instrumentId", rs.getObject("instrument_id"));
        m.put("calibratedOn", rs.getObject("calibrated_on", LocalDate.class));
        m.put("provider", rs.getString("provider"));
        m.put("internal", rs.getBoolean("internal"));
        m.put("certificateNumber", rs.getString("certificate_number"));
        m.put("points", read(rs.getString("points")));
        m.put("emt", rs.getBigDecimal("emt"));
        m.put("maxAbsError", rs.getBigDecimal("max_abs_error"));
        m.put("verdict", rs.getString("verdict"));
        m.put("nextDue", rs.getObject("next_due", LocalDate.class));
        m.put("ncId", rs.getObject("nc_id"));
        m.put("ncReference", rs.getString("nc_reference"));
        String file = rs.getString("cert_file_name");
        m.put("certificate", file == null ? null : Map.of("fileName", file, "contentType",
                rs.getString("cert_content_type"), "size", rs.getLong("cert_size"),
                "url", "/api/calibrations/" + rs.getObject("id") + "/certificate"));
        m.put("comment", rs.getString("comment"));
        m.put("createdBy", rs.getString("author"));
        m.put("createdAt", rs.getTimestamp("created_at").toInstant());
        return m;
    }

    // ------------------------------------------------------------------ effet instrument

    /**
     * Contrôles dont un résultat référence l'instrument depuis {@code since} (par défaut : date du dernier étalonnage
     * conforme ; sans étalonnage conforme, tout l'historique) et lots de ces contrôles.
     */
    @Transactional(readOnly = true)
    Map<String, Object> impact(UUID instrumentId, LocalDate since) {
        Map<String, Object> inst = instrument(instrumentId);
        Map<String, Object> lastOk = jdbc.sql(CAL_SELECT + """
                 where c.instrument_id = :id and c.verdict = 'conforme' order by c.calibrated_on desc,
                 c.created_at desc limit 1""").param("id", instrumentId).query((rs, n) -> calibrationView(rs))
                .optional().orElse(null);
        LocalDate from = since != null ? since : lastOk == null ? null : (LocalDate) lastOk.get("calibratedOn");
        OffsetDateTime fromTs = from == null ? OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                : from.atStartOfDay(ZONE).toOffsetDateTime();
        List<Map<String, Object>> inspections = jdbc.sql("""
                select i.id, i.point, i.decision, i.created_at, l.id lot_id, l.numero, a.code a_code,
                  string_agg(r.characteristic, ',' order by r.characteristic) chars, min(r.at) first_at
                from inspection_result r join inspection i on i.id = r.inspection_id
                join lot l on l.id = i.lot_id join article a on a.id = l.article_id
                where r.instrument_id = :id and r.at >= :from
                group by i.id, i.point, i.decision, i.created_at, l.id, l.numero, a.code
                order by min(r.at)""").param("id", instrumentId).param("from", fromTs)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("point", rs.getString("point"));
                    m.put("decision", rs.getString("decision"));
                    m.put("lotId", rs.getObject("lot_id"));
                    m.put("lotNumero", rs.getString("numero"));
                    m.put("articleCode", rs.getString("a_code"));
                    m.put("characteristics", List.of(rs.getString("chars").split(",")));
                    m.put("measuredAt", rs.getTimestamp("first_at").toInstant());
                    return m;
                }).list();
        List<Map<String, Object>> lotList = jdbc.sql("""
                select distinct l.id, l.numero, l.quality_status, l.quantity, l.unit, a.code a_code, a.label a_label,
                  p.code p_code, p.name p_name
                from inspection_result r join inspection i on i.id = r.inspection_id join lot l on l.id = i.lot_id
                join article a on a.id = l.article_id left join partner p on p.id = l.supplier_id
                where r.instrument_id = :id and r.at >= :from order by l.numero""")
                .param("id", instrumentId).param("from", fromTs)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("numero", rs.getString("numero"));
                    m.put("article", Map.of("code", rs.getString("a_code"), "label", rs.getString("a_label")));
                    m.put("supplier", rs.getString("p_code") == null ? null
                            : Map.of("code", rs.getString("p_code"), "name", rs.getString("p_name")));
                    m.put("qualityStatus", rs.getString("quality_status"));
                    m.put("quantity", rs.getBigDecimal("quantity"));
                    m.put("unit", rs.getString("unit"));
                    return m;
                }).list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrument", inst);
        out.put("since", from);
        out.put("lastConformingCalibration", lastOk == null ? null
                : Map.of("id", lastOk.get("id"), "calibratedOn", lastOk.get("calibratedOn")));
        out.put("inspections", inspections);
        out.put("lots", lotList);
        return out;
    }

    /** « Mettre ces lots en quarantaine pour réévaluation » : API lots M3b (historique, webhooks). */
    Map<String, Object> quarantine(UUID instrumentId, QuarantineInput in) {
        user.requireManager();
        Map<String, Object> impact = impact(instrumentId, in == null ? null : in.since());
        Map<String, Object> inst = instrument(instrumentId);
        String reason = in == null || blank(in.reason()) == null
                ? "Réévaluation : instrument " + inst.get("code") + " hors tolérance" : in.reason().strip();
        UUID nc = jdbc.sql("""
                select nc_id from calibration where instrument_id = :id and nc_id is not null
                order by calibrated_on desc, created_at desc limit 1""").param("id", instrumentId)
                .query(UUID.class).optional().orElse(null);
        List<Object> done = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impacted = (List<Map<String, Object>>) impact.get("lots");
        for (Map<String, Object> l : impacted) {
            (lots.quarantine((UUID) l.get("id"), reason, nc) ? done : skipped).add(Map.of("id", l.get("id"),
                    "numero", l.get("numero"), "previousStatus", l.get("qualityStatus")));
        }
        audit.record("INSTRUMENT_IMPACT_QUARANTINE", "instrument", instrumentId.toString(),
                write(Map.of("quarantined", done.size(), "skipped", skipped.size())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quarantined", done);
        out.put("skipped", skipped);
        out.put("ncId", nc);
        return out;
    }

    // ------------------------------------------------------------------ certificat

    void attachCertificate(UUID calibrationId, String name, String type, long size, String path) {
        jdbc.sql("""
                update calibration set cert_file_name = :n, cert_content_type = :t, cert_size = :s,
                  cert_storage_path = :p where id = :id""")
                .param("n", name).param("t", type).param("s", size).param("p", path).param("id", calibrationId)
                .update();
        audit.record("CALIBRATION_CERTIFICATE_ADDED", "calibration", calibrationId.toString(), null);
    }

    Map<String, Object> certificateMeta(UUID calibrationId) {
        return jdbc.sql("select cert_file_name, cert_content_type, cert_storage_path from calibration where id = :id")
                .param("id", calibrationId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fileName", rs.getString(1));
                    m.put("contentType", rs.getString(2));
                    m.put("path", rs.getString(3));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("Étalonnage"));
    }

    // ------------------------------------------------------------------ import

    ImportReport importFile(byte[] content, boolean dryRun) {
        user.requireManager();
        TabularFile.Table t = TabularFile.read(content, IMPORT_COLUMNS, List.of("code", "designation", "emt",
                "periodicite_mois"));
        Map<String, UUID> sites = new LinkedHashMap<>();
        jdbc.sql("select code, id from site").query((rs, n) -> sites.put(rs.getString(1), rs.getObject(2, UUID.class)))
                .list();
        Set<String> existing = new HashSet<>(jdbc.sql("select code from instrument").query(String.class).list());
        List<LineError> errors = new ArrayList<>();
        List<InstrumentInput> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int r = 0; r < t.rows().size(); r++) {
            List<String> row = t.rows().get(r);
            int line = r + 2;
            List<String> errs = new ArrayList<>();
            String site = t.cell(row, "site");
            if (!site.isEmpty() && !sites.containsKey(site)) {
                errs.add("site « " + site + " » inconnu");
            }
            InstrumentInput in = new InstrumentInput(t.cell(row, "code"), t.cell(row, "designation"),
                    blank(t.cell(row, "type")), sites.get(site), blank(t.cell(row, "emplacement")),
                    blank(t.cell(row, "numero_serie")), num(t.cell(row, "plage_min"), "plage_min", errs),
                    num(t.cell(row, "plage_max"), "plage_max", errs), blank(t.cell(row, "unite")),
                    num(t.cell(row, "resolution"), "resolution", errs), num(t.cell(row, "emt"), "emt", errs),
                    integer(t.cell(row, "periodicite_mois"), errs), blank(t.cell(row, "prestataire")), null,
                    date(t.cell(row, "prochaine_echeance"), errs));
            errs.addAll(validate(in, true));
            if (!seen.add(in.code())) {
                errs.add("code « " + in.code() + " » en double dans le fichier");
            }
            errs.forEach(e -> errors.add(new LineError(line, e)));
            items.add(in);
        }
        int updated = (int) items.stream().filter(i -> existing.contains(i.code())).count();
        boolean applied = errors.isEmpty() && !dryRun;
        if (applied) {
            items.forEach(this::insert);
            audit.record("INSTRUMENTS_IMPORTED", "instrument", null,
                    write(Map.of("created", items.size() - updated, "updated", updated)));
        }
        return new ImportReport(items.size() - updated, updated, List.copyOf(errors), applied, dryRun);
    }

    private static BigDecimal num(String v, String col, List<String> errs) {
        if (v.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(v.replace(',', '.'));
        } catch (NumberFormatException e) {
            errs.add(col + " : nombre attendu (« " + v + " »)");
            return null;
        }
    }

    private static Integer integer(String v, List<String> errs) {
        try {
            return v.isEmpty() ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            errs.add("periodicite_mois : entier attendu (« " + v + " »)");
            return null;
        }
    }

    private static LocalDate date(String v, List<String> errs) {
        try {
            return v.isEmpty() ? null : LocalDate.parse(v);
        } catch (RuntimeException e) {
            errs.add("prochaine_echeance : date AAAA-MM-JJ attendue (« " + v + " »)");
            return null;
        }
    }

    // ------------------------------------------------------------------ outils

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> read(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Instant now() {
        return Instant.now();
    }
}
