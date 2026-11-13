package com.essor.qms.traceability;

import com.essor.qms.traceability.LotService.Link;
import com.essor.qms.traceability.LotService.Lot;
import com.essor.qms.traceability.LotService.Ref;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Généalogie des lots par requêtes récursives (une requête pour les liens atteignables, une pour les lots) :
 * pas de N+1, cycles neutralisés par {@code UNION} (ensemble de lots déjà vus).
 */
final class Genealogy {

    private static final String LINK_SELECT = """
            select l.id, l.kind, l.source_lot_id, l.target_lot_id, l.quantity, l.reference, l.created_at,
              p.id p_id, p.code p_code, p.name p_name
            from lot_link l left join partner p on p.id = l.partner_id
            """;

    record Node(Lot lot, List<Child> children) {
    }

    record Child(Link link, Node node) {
    }

    private Genealogy() {
    }

    /** Vrai si {@code to} est atteignable depuis {@code from} en descendant (consumed_by, split_into). */
    static boolean reachable(JdbcClient jdbc, UUID from, UUID to) {
        return jdbc.sql("""
                with recursive d(lot_id) as (
                  select cast(:from as uuid)
                  union
                  select l.target_lot_id from lot_link l join d on l.source_lot_id = d.lot_id
                  where l.target_lot_id is not null)
                select count(*) from d where lot_id = :to""")
                .param("from", from).param("to", to).query(Long.class).single() > 0;
    }

    static List<Link> links(JdbcClient jdbc, String where, Map<String, ?> params) {
        return jdbc.sql(LINK_SELECT + " where " + where + " order by l.created_at, l.id").params(params)
                .query((rs, i) -> {
                    UUID p = rs.getObject("p_id", UUID.class);
                    return new Link(rs.getObject("id", UUID.class), rs.getString("kind"),
                            rs.getObject("source_lot_id", UUID.class), rs.getObject("target_lot_id", UUID.class),
                            p == null ? null : new Ref(p, rs.getString("p_code"), rs.getString("p_name")),
                            rs.getBigDecimal("quantity"), rs.getString("reference"),
                            rs.getTimestamp("created_at").toInstant());
                }).list();
    }

    /** Ids des lots atteignables (lot de départ compris), en descendant ou en remontant. */
    static Set<UUID> reachableLots(JdbcClient jdbc, UUID start, boolean down) {
        String step = down
                ? "select l.target_lot_id from lot_link l join r on l.source_lot_id = r.lot_id "
                + "where l.target_lot_id is not null"
                : "select l.source_lot_id from lot_link l join r on l.target_lot_id = r.lot_id";
        return new HashSet<>(jdbc.sql("with recursive r(lot_id) as (select cast(:start as uuid) union " + step
                + ") select lot_id from r").param("start", start).query(UUID.class).list());
    }

    static Map<UUID, Lot> lots(JdbcClient jdbc, Set<UUID> ids) {
        Map<UUID, Lot> lots = new HashMap<>();
        jdbc.sql(LotService.LOT_SELECT + " where l.id = any(:ids)").param("ids", ids.toArray(UUID[]::new))
                .query((rs, i) -> LotService.map(rs)).list().forEach(l -> lots.put(l.id(), l));
        return lots;
    }

    /** Arbre {@code {lot, children:[{link, node}]}} ; pour shipped_to (descendant) {@code node} est nul. */
    static Node tree(JdbcClient jdbc, UUID start, boolean down, int depth) {
        Set<UUID> ids = reachableLots(jdbc, start, down);
        Map<UUID, Lot> lots = lots(jdbc, ids);
        UUID[] arr = ids.toArray(UUID[]::new);
        List<Link> links = links(jdbc, down ? "l.source_lot_id = any(:ids)" : "l.target_lot_id = any(:ids)",
                Map.of("ids", arr));
        Map<UUID, List<Link>> byLot = new HashMap<>();
        for (Link l : links) {
            byLot.computeIfAbsent(down ? l.sourceLotId() : l.targetLotId(), k -> new ArrayList<>()).add(l);
        }
        return build(start, down, depth, lots, byLot, new HashSet<>());
    }

    private static Node build(UUID id, boolean down, int depth, Map<UUID, Lot> lots, Map<UUID, List<Link>> byLot,
                              Set<UUID> path) {
        List<Child> children = new ArrayList<>();
        if (depth > 0 && path.add(id)) {
            for (Link l : byLot.getOrDefault(id, List.of())) {
                UUID next = down ? l.targetLotId() : l.sourceLotId();
                Node child = next == null || !lots.containsKey(next) ? null
                        : build(next, down, depth - 1, lots, byLot, path);
                children.add(new Child(l, child));
            }
            path.remove(id);
        }
        return new Node(lots.get(id), List.copyOf(children));
    }

    // ------------------------------------------------------------------ rappel

    record ImpactedLot(UUID id, String numero, Map<String, Object> article, String qualityStatus,
                       BigDecimal quantity, BigDecimal remaining, String unit, Ref supplier, String supplierLot) {
    }

    record ClientImpact(Ref partner, BigDecimal quantite, List<String> references) {
    }

    record UnitQty(String unit, BigDecimal qty) {
    }

    /** Quantités regroupées par unité (jamais de somme entre unités différentes), triées par unité. */
    record Quantities(List<UnitQty> enStock, List<UnitQty> enCours, List<UnitQty> expedie) {
    }

    record Recall(Lot source, List<ImpactedLot> lotsImpactes, List<ClientImpact> clients, Quantities quantites) {
    }

    /**
     * Restant d'un lot = quantité − somme de ses liens sortants (≥ 0). {@code enStock} : restant des lots LIBERE,
     * QUARANTAINE ou DEROGATION ; {@code enCours} : restant des lots EN_ATTENTE ; {@code expedie} : quantités
     * shipped_to. Les lots REFUSE / DETRUIT sont listés mais hors stock.
     */
    static Recall recall(JdbcClient jdbc, UUID start) {
        Set<UUID> ids = reachableLots(jdbc, start, true);
        Map<UUID, Lot> lots = lots(jdbc, ids);
        UUID[] arr = ids.toArray(UUID[]::new);
        List<Link> out = links(jdbc, "l.source_lot_id = any(:ids)", Map.of("ids", arr));
        Map<UUID, BigDecimal> used = new HashMap<>();
        Map<UUID, ClientAcc> clients = new LinkedHashMap<>();
        Map<String, BigDecimal> shipped = new java.util.TreeMap<>();
        for (Link l : out) {
            used.merge(l.sourceLotId(), l.quantity(), BigDecimal::add);
            if ("shipped_to".equals(l.kind())) {
                Lot src = lots.get(l.sourceLotId());
                shipped.merge(unitOf(src), l.quantity(), BigDecimal::add);
                ClientAcc acc = clients.computeIfAbsent(l.partner().id(), k -> new ClientAcc(l.partner()));
                acc.qty = acc.qty.add(l.quantity());
                if (l.reference() != null && !acc.refs.contains(l.reference())) {
                    acc.refs.add(l.reference());
                }
            }
        }
        Map<String, BigDecimal> stock = new java.util.TreeMap<>();
        Map<String, BigDecimal> wip = new java.util.TreeMap<>();
        List<ImpactedLot> impacted = new ArrayList<>();
        for (Lot lot : lots.values()) {
            BigDecimal remaining = lot.quantity().subtract(used.getOrDefault(lot.id(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            switch (lot.qualityStatus()) {
                case "LIBERE", "QUARANTAINE", "DEROGATION" -> stock.merge(unitOf(lot), remaining, BigDecimal::add);
                case "EN_ATTENTE" -> wip.merge(unitOf(lot), remaining, BigDecimal::add);
                default -> { }
            }
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("id", lot.article().id());
            article.put("code", lot.article().code());
            article.put("label", lot.article().label());
            impacted.add(new ImpactedLot(lot.id(), lot.numero(), article, lot.qualityStatus(), lot.quantity(),
                    remaining, lot.unit(), lot.supplier(), lot.supplierLot()));
        }
        impacted.sort((a, b) -> a.id().equals(start) ? -1 : b.id().equals(start) ? 1
                : a.numero().compareTo(b.numero()));
        List<ClientImpact> cl = clients.values().stream()
                .map(c -> new ClientImpact(c.partner, c.qty, List.copyOf(c.refs))).toList();
        return new Recall(lots.get(start), List.copyOf(impacted), cl, new Quantities(byUnit(stock), byUnit(wip), byUnit(shipped)));
    }

    private static String unitOf(Lot lot) {
        return lot == null || lot.unit() == null ? "" : lot.unit();
    }

    private static List<UnitQty> byUnit(Map<String, BigDecimal> m) {
        return m.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .map(e -> new UnitQty(e.getKey(), e.getValue())).toList();
    }

    private static final class ClientAcc {
        private final Ref partner;
        private BigDecimal qty = BigDecimal.ZERO;
        private final List<String> refs = new ArrayList<>();

        ClientAcc(Ref partner) {
            this.partner = partner;
        }
    }
}
