package com.essor.qms.partners;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grilles d'évaluation paramétrables : critères pondérés notés de 0 à {@code scaleMax} ; un critère {@code auto}
 * est noté par l'indicateur calculé et ses tranches ({@code bands} : première tranche dont {@code max} ≥ valeur,
 * {@code max} nul = au-delà) ; classes par note globale minimale (0-100) ; classes déclenchant un plan de progrès.
 * Grilles {@code FOURNISSEUR} et {@code CLIENT} créées à la volée.
 */
@Component
class EvaluationGrids {

    static final Set<String> AUTO = Set.of("nc_count", "lots_refused_pct", "lots_derogation", "late_deliveries",
            "complaints");

    record Band(Double max, int score) {
    }

    record Criterion(String code, String label, double weight, String auto, List<Band> bands) {
    }

    record GradeClass(String code, String label, double min) {
    }

    record Grid(UUID id, String code, String label, String partnerType, int scaleMax, List<Criterion> criteria,
                List<GradeClass> classes, List<String> improvementClasses) {
    }

    record GridInput(String code, String label, String partnerType, Integer scaleMax, List<Criterion> criteria,
                     List<GradeClass> classes, List<String> improvementClasses) {
    }

    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final AuditLog audit;
    private final ObjectMapper json;

    EvaluationGrids(JdbcClient jdbc, CurrentUser user, AuditLog audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Grid> all() {
        ensureDefaults();
        return jdbc.sql("""
                select id, code, label, partner_type, scale_max, criteria, classes, improvement_classes
                from evaluation_grid order by code""")
                .query((rs, i) -> new Grid(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), List.of(read(rs.getString(6), Criterion[].class)),
                        List.of(read(rs.getString(7), GradeClass[].class)), strings(rs.getArray(8)))).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Grid find(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) {
            throw ApiException.badRequest("Grille d'évaluation obligatoire (code ou identifiant)");
        }
        return all().stream().filter(g -> g.code().equals(codeOrId) || g.id().toString().equals(codeOrId))
                .findFirst().orElseThrow(() -> ApiException.badRequest("Grille d'évaluation inconnue : " + codeOrId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Grid put(String code, GridInput in) {
        user.requireManager();
        if (code == null || !code.matches("^[A-Z0-9_-]{1,50}$")) {
            throw ApiException.badRequest("Code de grille : majuscules, chiffres, - ou _ (50 maximum)");
        }
        validate(in);
        save(code, in);
        audit.record("EVALUATION_GRID_UPDATED", "evaluation_grid", code, null);
        return find(code);
    }

    private void save(String code, GridInput in) {
        jdbc.sql("""
                insert into evaluation_grid (tenant_id, code, label, partner_type, scale_max, criteria, classes,
                  improvement_classes)
                values (app_current_tenant(), :c, :l, :t, :s, cast(:cr as jsonb), cast(:cl as jsonb), :imp)
                on conflict (tenant_id, code) do update set label = excluded.label,
                  partner_type = excluded.partner_type, scale_max = excluded.scale_max, criteria = excluded.criteria,
                  classes = excluded.classes, improvement_classes = excluded.improvement_classes, updated_at = now()""")
                .param("c", code).param("l", in.label().strip()).param("t", in.partnerType())
                .param("s", in.scaleMax()).param("cr", write(in.criteria())).param("cl", write(in.classes()))
                .param("imp", in.improvementClasses().toArray(String[]::new)).update();
    }

    private static void validate(GridInput in) {
        List<String> e = new ArrayList<>();
        if (in.label() == null || in.label().isBlank() || in.label().length() > 200) {
            e.add("libellé obligatoire");
        }
        if (!Set.of("fournisseur", "client").contains(in.partnerType() == null ? "" : in.partnerType())) {
            e.add("partnerType : fournisseur ou client");
        }
        if (in.scaleMax() == null || in.scaleMax() < 1 || in.scaleMax() > 100) {
            e.add("scaleMax : 1 à 100");
        }
        if (in.criteria() == null || in.criteria().isEmpty() || in.criteria().size() > 30) {
            e.add("1 à 30 critères");
        } else {
            Set<String> codes = new HashSet<>();
            for (Criterion c : in.criteria()) {
                if (c.code() == null || !c.code().matches("^[a-z][a-z0-9_]{0,29}$") || !codes.add(c.code())
                        || c.label() == null || c.weight() <= 0) {
                    e.add("critère : code unique (minuscules), libellé et poids > 0");
                    break;
                }
                if (c.auto() != null && (!AUTO.contains(c.auto()) || c.bands() == null || c.bands().isEmpty())) {
                    e.add("critère " + c.code() + " : indicateur automatique (" + String.join(", ", AUTO)
                            + ") et tranches obligatoires");
                }
            }
        }
        if (in.classes() == null || in.classes().isEmpty()) {
            e.add("classes obligatoires");
        }
        if (in.improvementClasses() == null) {
            e.add("improvementClasses obligatoire (éventuellement vide)");
        } else if (in.classes() != null && !in.classes().stream().map(GradeClass::code).toList()
                .containsAll(in.improvementClasses())) {
            e.add("improvementClasses : classes inconnues");
        }
        if (!e.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", e));
        }
    }

    private void ensureDefaults() {
        Set<String> codes = new HashSet<>(jdbc.sql("select code from evaluation_grid").query(String.class).list());
        List<GradeClass> abc = List.of(new GradeClass("A", "Agréé", 80), new GradeClass("B", "Sous surveillance", 60),
                new GradeClass("C", "Refusé", 0));
        if (!codes.contains("FOURNISSEUR")) {
            save("FOURNISSEUR", new GridInput(null, "Évaluation fournisseur trimestrielle", "fournisseur", 4, List.of(
                    new Criterion("qualite_service", "Qualité de service", 3, null, null),
                    new Criterion("reactivite", "Réactivité", 2, null, null),
                    new Criterion("nc", "Non-conformités", 2, "nc_count",
                            List.of(new Band(0.0, 4), new Band(1.0, 3), new Band(3.0, 1), new Band(null, 0))),
                    new Criterion("lots_refuses", "Lots refusés", 2, "lots_refused_pct",
                            List.of(new Band(0.0, 4), new Band(5.0, 2), new Band(null, 0))),
                    new Criterion("retards", "Retards de livraison", 1, "late_deliveries",
                            List.of(new Band(0.0, 4), new Band(2.0, 2), new Band(null, 0)))),
                    abc, List.of("C")));
        }
        if (!codes.contains("CLIENT")) {
            save("CLIENT", new GridInput(null, "Satisfaction client", "client", 5, List.of(
                    new Criterion("accueil", "Accueil et écoute", 1, null, null),
                    new Criterion("delais", "Respect des délais", 2, null, null),
                    new Criterion("qualite_produit", "Qualité des produits", 3, null, null),
                    new Criterion("reclamations", "Réclamations", 2, "complaints",
                            List.of(new Band(0.0, 5), new Band(2.0, 3), new Band(null, 0)))),
                    abc, List.of("C")));
        }
    }

    private <T> T read(String s, Class<T> type) {
        try {
            return json.readValue(s, type);
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

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }
}
