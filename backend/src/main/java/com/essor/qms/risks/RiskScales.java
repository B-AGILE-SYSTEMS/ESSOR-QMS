package com.essor.qms.risks;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Échelles de cotation paramétrables : dimensions (niveaux 1..n libellés, {@code inFormula}), formule = produit des
 * dimensions retenues, seuils de criticité ({@code min} inclus) et couleurs. Échelle {@code DEFAUT} créée à la volée
 * (gravité × probabilité sur 4 niveaux, maîtrise hors formule).
 */
@Component
class RiskScales {

    static final String DEFAULT_CODE = "DEFAUT";
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,29}$");

    record Level(int value, String label) {
    }

    record Dimension(String code, String label, boolean inFormula, List<Level> levels) {
    }

    record Threshold(String level, String label, int min, String color) {
    }

    record Scale(UUID id, String code, String label, List<Dimension> dimensions, List<Threshold> thresholds) {

        List<Dimension> formula() {
            return dimensions.stream().filter(Dimension::inFormula).toList();
        }

        /** Seuil atteint par un score (le plus haut dont min ≤ score). */
        Threshold threshold(int score) {
            Threshold t = thresholds.get(0);
            for (Threshold x : thresholds) {
                if (score >= x.min()) {
                    t = x;
                }
            }
            return t;
        }
    }

    record ScaleInput(String label, List<Dimension> dimensions, List<Threshold> thresholds) {
    }

    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final AuditLog audit;
    private final ObjectMapper json;

    RiskScales(JdbcClient jdbc, CurrentUser user, AuditLog audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Scale> all() {
        ensureDefault();
        return jdbc.sql("select id, code, label, dimensions, thresholds from risk_scale order by code")
                .query((rs, i) -> map(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5))).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Scale byCode(String code) {
        return all().stream().filter(s -> s.code().equals(code == null ? DEFAULT_CODE : code)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Échelle de cotation inconnue : " + code));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Scale byId(UUID id) {
        return all().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Échelle"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Scale put(String code, ScaleInput in) {
        user.requireManager();
        if (code == null || !code.matches("^[A-Z0-9_-]{1,50}$")) {
            throw ApiException.badRequest("Code d'échelle : majuscules, chiffres, - ou _ (50 maximum)");
        }
        validate(in);
        jdbc.sql("""
                insert into risk_scale (tenant_id, code, label, dimensions, thresholds)
                values (app_current_tenant(), :c, :l, cast(:d as jsonb), cast(:t as jsonb))
                on conflict (tenant_id, code) do update set label = excluded.label, dimensions = excluded.dimensions,
                  thresholds = excluded.thresholds, updated_at = now()""")
                .param("c", code).param("l", in.label().strip()).param("d", write(in.dimensions()))
                .param("t", write(in.thresholds())).update();
        audit.record("RISK_SCALE_UPDATED", "risk_scale", code, null);
        return byCode(code);
    }

    private static void validate(ScaleInput in) {
        List<String> e = new ArrayList<>();
        if (in.label() == null || in.label().isBlank() || in.label().length() > 200) {
            e.add("libellé obligatoire");
        }
        if (in.dimensions() == null || in.dimensions().isEmpty() || in.dimensions().size() > 6) {
            e.add("1 à 6 dimensions");
        } else {
            Set<String> codes = new HashSet<>();
            for (Dimension d : in.dimensions()) {
                if (d.code() == null || !CODE.matcher(d.code()).matches() || !codes.add(d.code())) {
                    e.add("code de dimension invalide ou en double : " + d.code());
                }
                if (d.levels() == null || d.levels().size() < 2 || d.levels().size() > 10) {
                    e.add("dimension " + d.code() + " : 2 à 10 niveaux");
                } else {
                    for (int i = 0; i < d.levels().size(); i++) {
                        if (d.levels().get(i).value() != i + 1 || d.levels().get(i).label() == null) {
                            e.add("dimension " + d.code() + " : niveaux 1..n libellés attendus");
                            break;
                        }
                    }
                }
            }
            if (in.dimensions().stream().filter(Dimension::inFormula).count() < 2) {
                e.add("au moins deux dimensions dans la formule (axes de la matrice)");
            }
        }
        if (in.thresholds() == null || in.thresholds().isEmpty()) {
            e.add("seuils de criticité obligatoires");
        } else {
            int prev = Integer.MIN_VALUE;
            for (Threshold t : in.thresholds()) {
                if (t.level() == null || t.level().isBlank() || t.color() == null
                        || !COLOR.matcher(t.color()).matches() || t.min() <= prev) {
                    e.add("seuils : niveau, couleur #RRGGBB et minimum strictement croissant");
                    break;
                }
                prev = t.min();
            }
        }
        if (!e.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", e));
        }
    }

    /** Score et niveau d'une cotation ; toutes les dimensions de la formule sont obligatoires. */
    static Map<String, Object> rate(Scale s, Map<String, Integer> ratings, String what) {
        if (ratings == null) {
            throw ApiException.badRequest("Cotation " + what + " obligatoire");
        }
        Map<String, Integer> clean = new LinkedHashMap<>();
        int score = 1;
        for (Dimension d : s.dimensions()) {
            Integer v = ratings.get(d.code());
            if (v == null && !d.inFormula()) {
                continue;
            }
            if (v == null || v < 1 || v > d.levels().size()) {
                throw ApiException.badRequest("Cotation " + what + " : " + d.code() + " entre 1 et "
                        + d.levels().size() + " attendue");
            }
            clean.put(d.code(), v);
            if (d.inFormula()) {
                score *= v;
            }
        }
        for (String k : ratings.keySet()) {
            if (s.dimensions().stream().noneMatch(d -> d.code().equals(k))) {
                throw ApiException.badRequest("Cotation " + what + " : dimension inconnue " + k);
            }
        }
        Threshold t = s.threshold(score);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ratings", clean);
        out.put("score", score);
        out.put("level", t.level());
        out.put("levelLabel", t.label());
        out.put("color", t.color());
        return out;
    }

    private void ensureDefault() {
        if (jdbc.sql("select count(*) from risk_scale where code = :c").param("c", DEFAULT_CODE).query(Long.class)
                .single() > 0) {
            return;
        }
        List<Level> four = List.of(new Level(1, "Faible"), new Level(2, "Modérée"), new Level(3, "Forte"),
                new Level(4, "Très forte"));
        List<Dimension> dims = List.of(
                new Dimension("gravite", "Gravité", true, List.of(new Level(1, "Bénigne"),
                        new Level(2, "Sérieuse"), new Level(3, "Grave"), new Level(4, "Très grave"))),
                new Dimension("probabilite", "Probabilité", true, List.of(new Level(1, "Rare"),
                        new Level(2, "Possible"), new Level(3, "Probable"), new Level(4, "Fréquente"))),
                new Dimension("maitrise", "Maîtrise", false, four));
        List<Threshold> th = List.of(new Threshold("faible", "Faible", 1, "#2E7D32"),
                new Threshold("moyen", "Moyen", 4, "#F9A825"), new Threshold("eleve", "Élevé", 8, "#EF6C00"),
                new Threshold("critique", "Critique", 12, "#C62828"));
        jdbc.sql("""
                insert into risk_scale (tenant_id, code, label, dimensions, thresholds)
                values (app_current_tenant(), :c, 'Gravité × probabilité (4 × 4)', cast(:d as jsonb),
                  cast(:t as jsonb)) on conflict do nothing""")
                .param("c", DEFAULT_CODE).param("d", write(dims)).param("t", write(th)).update();
    }

    private Scale map(UUID id, String code, String label, String dims, String th) {
        try {
            return new Scale(id, code, label, List.of(json.readValue(dims, Dimension[].class)),
                    List.of(json.readValue(th, Threshold[].class)));
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
