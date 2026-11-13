package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lots, statut qualité et son historique, liens de généalogie. Chaque changement de statut part en webhook. */
@Service
@Transactional
class LotService {

    static final Set<String> STATUSES = Set.of("EN_ATTENTE", "LIBERE", "QUARANTAINE", "DEROGATION", "REFUSE",
            "DETRUIT");
    static final Set<String> KINDS = Set.of("consumed_by", "shipped_to", "split_into");
    /** Statuts pour lesquels un motif est obligatoire. */
    private static final Set<String> REASON_REQUIRED = Set.of("QUARANTAINE", "DEROGATION", "REFUSE", "DETRUIT");

    record Ref(UUID id, String code, String name) {
    }

    record ArticleRef(UUID id, String code, String label, String unit, String family) {
    }

    record Lot(UUID id, ArticleRef article, String numero, Ref supplier, String supplierLot, BigDecimal quantity,
               String unit, LocalDate dlc, Ref site, String qualityStatus, Instant createdAt, Instant updatedAt) {
    }

    record HistoryEntry(Instant at, String from, String to, String reason, String author, UUID ncId,
                        UUID inspectionId) {
    }

    record LotDetail(Lot lot, List<HistoryEntry> history, List<Map<String, Object>> inspections) {
    }

    record CreateLot(UUID articleId, String numero, UUID supplierId, String supplierLot, BigDecimal quantity,
                     String unit, LocalDate dlc, UUID siteId) {
    }

    record StatusChange(String status, String reason, UUID ncId) {
    }

    record CreateLink(String kind, UUID targetLotId, UUID partnerId, BigDecimal quantity, String reference) {
    }

    record Link(UUID id, String kind, UUID sourceLotId, UUID targetLotId, Ref partner, BigDecimal quantity,
                String reference, Instant createdAt) {
    }

    static final String LOT_SELECT = """
            select l.id, l.numero, l.supplier_lot, l.quantity, l.unit, l.dlc, l.quality_status, l.created_at,
              l.updated_at, a.id a_id, a.code a_code, a.label a_label, a.unit a_unit, a.family a_family,
              p.id p_id, p.code p_code, p.name p_name, s.id s_id, s.code s_code, s.name s_name
            from lot l join article a on a.id = l.article_id
            left join partner p on p.id = l.supplier_id
            left join site s on s.id = l.site_id
            """;

    private final JdbcClient jdbc;
    private final TraceSupport support;
    private final AuditLog audit;
    private final WebhookService webhooks;
    private final ObjectMapper json;

    LotService(JdbcClient jdbc, TraceSupport support, AuditLog audit, WebhookService webhooks, ObjectMapper json) {
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
        this.webhooks = webhooks;
        this.json = json;
    }

    Lot create(CreateLot in) {
        support.requireWriter();
        if (in.articleId() == null || in.numero() == null || in.numero().isBlank() || in.numero().length() > 80) {
            throw ApiException.badRequest("Article et numéro de lot (80 caractères maximum) obligatoires");
        }
        if (in.quantity() == null || in.quantity().signum() < 0) {
            throw ApiException.badRequest("Quantité positive obligatoire");
        }
        Map<String, Object> article = jdbc.sql("select unit, has_dlc from article where id = :id")
                .param("id", in.articleId())
                .query((rs, i) -> Map.<String, Object>of("unit", rs.getString(1), "dlc", rs.getBoolean(2)))
                .optional().orElseThrow(() -> ApiException.badRequest("Article inconnu"));
        if (Boolean.TRUE.equals(article.get("dlc")) && in.dlc() == null) {
            throw ApiException.badRequest("DLC obligatoire pour cet article");
        }
        if (in.supplierId() != null) {
            Map<String, Object> p = support.partnerRef(in.supplierId());
            if (p == null || !"fournisseur".equals(p.get("type"))) {
                throw ApiException.badRequest("Fournisseur inconnu");
            }
        }
        if (in.siteId() == null) {
            throw ApiException.badRequest("Site obligatoire");
        }
        if (!support.siteExists(in.siteId())) {
            throw ApiException.badRequest("Site inconnu");
        }
        boolean dup = jdbc.sql("select count(*) from lot where article_id = :a and numero = :n")
                .param("a", in.articleId()).param("n", in.numero().strip()).query(Long.class).single() > 0;
        if (dup) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate", "Ce numéro de lot existe déjà pour cet article");
        }
        UUID id = jdbc.sql("""
                insert into lot (tenant_id, article_id, numero, supplier_id, supplier_lot, quantity, unit, dlc, site_id,
                  created_by)
                values (app_current_tenant(), :a, :n, :sup, :supLot, :q, :u, :dlc, :site, app_current_user())
                returning id""")
                .param("a", in.articleId()).param("n", in.numero().strip()).param("sup", in.supplierId())
                .param("supLot", blankToNull(in.supplierLot())).param("q", in.quantity())
                .param("u", in.unit() == null || in.unit().isBlank() ? article.get("unit") : in.unit().strip())
                .param("dlc", in.dlc()).param("site", in.siteId()).query(UUID.class).single();
        jdbc.sql("""
                insert into lot_status_history (tenant_id, lot_id, from_status, to_status, reason, actor_id)
                values (app_current_tenant(), :id, null, 'EN_ATTENTE', 'Création du lot', app_current_user())""")
                .param("id", id).update();
        audit.record("LOT_CREATED", "lot", id.toString(), null);
        return lot(id);
    }

    @Transactional(readOnly = true)
    List<Lot> list(String q, String status, UUID articleId) {
        return jdbc.sql(LOT_SELECT + """
                where (:q = '' or l.numero ilike '%' || :q || '%' or l.supplier_lot ilike '%' || :q || '%'
                       or a.code ilike '%' || :q || '%')
                  and (:status = '' or l.quality_status = :status)
                  and (cast(:article as uuid) is null or l.article_id = cast(:article as uuid))
                order by l.created_at desc limit 200""")
                .param("q", q == null ? "" : q.strip()).param("status", status == null ? "" : status)
                .param("article", articleId)
                .query((rs, i) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    Lot lot(UUID id) {
        return jdbc.sql(LOT_SELECT + " where l.id = :id").param("id", id).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("Lot"));
    }

    @Transactional(readOnly = true)
    LotDetail detail(UUID id) {
        Lot lot = lot(id);
        List<HistoryEntry> history = jdbc.sql("""
                select h.at, h.from_status, h.to_status, h.reason, u.display_name, h.nc_id, h.inspection_id
                from lot_status_history h left join app_user u on u.id = h.actor_id
                where h.lot_id = :id order by h.at, h.id""").param("id", id)
                .query((rs, i) -> new HistoryEntry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)))
                .list();
        List<Map<String, Object>> inspections = jdbc.sql("""
                select i.id, i.point, i.decision, i.created_at, i.decided_at, i.nc_id, c.code, c.version, c.label
                from inspection i join control_plan c on c.id = i.control_plan_id
                where i.lot_id = :id order by i.created_at""").param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("point", rs.getString("point"));
                    m.put("controlPlan", Map.of("code", rs.getString("code"), "version", rs.getInt("version"),
                            "label", rs.getString("label")));
                    m.put("decision", rs.getString("decision"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    m.put("decidedAt", rs.getTimestamp("decided_at") == null ? null
                            : rs.getTimestamp("decided_at").toInstant());
                    m.put("ncId", rs.getObject("nc_id"));
                    return m;
                }).list();
        return new LotDetail(lot, history, inspections);
    }

    /** Changement manuel : DEROGATION exige un motif et le rôle QUALITY_MANAGER. */
    Lot changeStatus(UUID id, StatusChange in) {
        support.requireWriter();
        String status = in.status() == null ? "" : in.status();
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("Statut inconnu : " + String.join(", ", STATUSES.stream().sorted().toList()));
        }
        if ("DEROGATION".equals(status)) {
            support.requireAny(TraceSupport.QUALITY_MANAGER);
        }
        if (in.ncId() != null && jdbc.sql("select count(*) from record where id = :id and type_code = 'NC'")
                .param("id", in.ncId()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("NC inconnue");
        }
        applyStatus(id, status, in.reason(), in.ncId(), null);
        return lot(id);
    }

    /** Applique un statut (manuel, décision de contrôle ou blocage de rappel) : historique, audit, webhook. */
    void applyStatus(UUID id, String status, String reason, UUID ncId, UUID inspectionId) {
        String r = blankToNull(reason);
        if (REASON_REQUIRED.contains(status) && r == null) {
            throw ApiException.badRequest("Motif obligatoire pour le statut " + status);
        }
        if (r != null && r.length() > 2000) {
            throw ApiException.badRequest("Motif : 2000 caractères maximum");
        }
        Lot lot = jdbc.sql(LOT_SELECT + " where l.id = :id for update of l").param("id", id)
                .query((rs, i) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("Lot"));
        if ("DETRUIT".equals(lot.qualityStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "lot_final", "Un lot détruit ne change plus de statut");
        }
        if (status.equals(lot.qualityStatus())) {
            return;
        }
        jdbc.sql("update lot set quality_status = :s, updated_at = now() where id = :id")
                .param("s", status).param("id", id).update();
        jdbc.sql("""
                insert into lot_status_history (tenant_id, lot_id, from_status, to_status, reason, nc_id, inspection_id,
                  actor_id)
                values (app_current_tenant(), :id, :from, :to, :reason, :nc, :insp, app_current_user())""")
                .param("id", id).param("from", lot.qualityStatus()).param("to", status).param("reason", r)
                .param("nc", ncId).param("insp", inspectionId).update();
        audit.record("LOT_STATUS_CHANGED", "lot", id.toString(), write(Map.of("from", lot.qualityStatus(),
                "to", status)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "lot.status_changed");
        payload.put("lotId", id);
        payload.put("numero", lot.numero());
        payload.put("article", lot.article().code());
        payload.put("status", status);
        payload.put("reason", r);
        payload.put("at", Instant.now().toString());
        webhooks.enqueue("lot.status_changed", write(payload));
    }

    Link addLink(UUID sourceId, CreateLink in) {
        support.requireWriter();
        lot(sourceId);
        String kind = in.kind() == null ? "" : in.kind();
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("kind : consumed_by, shipped_to ou split_into");
        }
        if (in.quantity() == null || in.quantity().signum() <= 0) {
            throw ApiException.badRequest("Quantité strictement positive obligatoire");
        }
        if (in.reference() != null && in.reference().length() > 120) {
            throw ApiException.badRequest("Référence : 120 caractères maximum");
        }
        UUID target = null;
        UUID partner = null;
        if ("shipped_to".equals(kind)) {
            Map<String, Object> p = support.partnerRef(in.partnerId());
            if (p == null || !"client".equals(p.get("type"))) {
                throw ApiException.badRequest("Client (partnerId) obligatoire pour une expédition");
            }
            partner = in.partnerId();
        } else {
            if (in.targetLotId() == null || in.targetLotId().equals(sourceId)) {
                throw ApiException.badRequest("Lot cible (targetLotId) obligatoire et différent du lot source");
            }
            lot(in.targetLotId());
            if (Genealogy.reachable(jdbc, in.targetLotId(), sourceId)) {
                throw new ApiException(HttpStatus.CONFLICT, "genealogy_cycle",
                        "Ce lien créerait une boucle dans la généalogie");
            }
            target = in.targetLotId();
        }
        UUID id = jdbc.sql("""
                insert into lot_link (tenant_id, source_lot_id, kind, target_lot_id, partner_id, quantity, reference,
                  created_by)
                values (app_current_tenant(), :s, :k, :t, :p, :q, :r, app_current_user()) returning id""")
                .param("s", sourceId).param("k", kind).param("t", target).param("p", partner)
                .param("q", in.quantity()).param("r", blankToNull(in.reference())).query(UUID.class).single();
        audit.record("LOT_LINK_CREATED", "lot", sourceId.toString(), write(Map.of("link", id, "kind", kind)));
        return Genealogy.links(jdbc, "l.id = :id", Map.of("id", id)).get(0);
    }

    static Lot map(ResultSet rs) throws SQLException {
        UUID supplier = rs.getObject("p_id", UUID.class);
        UUID site = rs.getObject("s_id", UUID.class);
        return new Lot(rs.getObject("id", UUID.class),
                new ArticleRef(rs.getObject("a_id", UUID.class), rs.getString("a_code"), rs.getString("a_label"),
                        rs.getString("a_unit"), rs.getString("a_family")),
                rs.getString("numero"),
                supplier == null ? null : new Ref(supplier, rs.getString("p_code"), rs.getString("p_name")),
                rs.getString("supplier_lot"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getObject("dlc", LocalDate.class),
                site == null ? null : new Ref(site, rs.getString("s_code"), rs.getString("s_name")),
                rs.getString("quality_status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
