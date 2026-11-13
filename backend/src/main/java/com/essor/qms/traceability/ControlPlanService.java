package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans de contrôle versionnés : un PUT crée la version suivante et désactive la précédente. */
@Service
@Transactional
class ControlPlanService {

    static final Set<String> POINTS = Set.of("RECEPTION", "STOCKAGE", "PREPARATION", "PRODUCTION_ENTREE",
            "PRODUCTION_ENCOURS", "PRODUCTION_SORTIE", "EXPEDITION");
    static final Set<String> TARGETS = Set.of("article", "famille", "fournisseur", "client");
    static final Set<String> TYPES = Set.of("mesure", "attribut", "document");
    static final Set<String> CRITICITES = Set.of("critique", "majeure", "mineure");

    record Characteristic(String code, String libelle, String type, Double nominal, Double tolMin, Double tolMax,
                          String unite, String instrumentRequis, String criticite) {
    }

    /** {@code mode} fixe : {@code taille} ; iso2859 : {@code nqa}, {@code niveau} (II seul pris en charge). */
    record Sampling(String mode, Integer taille, String nqa, String niveau) {
    }

    /** Contrôle en-cours : toutes les {@code pieces} pièces ou toutes les {@code minutes} minutes. */
    record Frequency(Integer pieces, Integer minutes) {
    }

    record Target(String type, UUID id, String famille) {
    }

    record PlanInput(String code, String label, String point, Target target, List<Characteristic> characteristics,
                     Sampling sampling, Frequency frequency) {
    }

    record Plan(UUID id, String code, int version, String label, String point, Target target,
                List<Characteristic> characteristics, Sampling sampling, Frequency frequency, boolean active,
                Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final TraceSupport support;
    private final AuditLog audit;
    private final ObjectMapper json;

    ControlPlanService(JdbcClient jdbc, TraceSupport support, AuditLog audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    List<Plan> list(String point, boolean all) {
        return jdbc.sql("select * from control_plan where (:point = '' or point = :point) and (:all or active) "
                        + "order by code, version desc")
                .param("point", point == null ? "" : point).param("all", all)
                .query((rs, i) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    Plan get(UUID id) {
        return jdbc.sql("select * from control_plan where id = :id").param("id", id).query((rs, i) -> map(rs))
                .optional().orElseThrow(() -> ApiException.notFound("Plan de contrôle"));
    }

    Plan create(PlanInput in) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        validate(in);
        boolean exists = jdbc.sql("select count(*) from control_plan where code = :c").param("c", in.code().strip())
                .query(Long.class).single() > 0;
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate", "Un plan porte déjà ce code");
        }
        return insert(in, 1);
    }

    /** Nouvelle version du plan {@code id} (qui doit être la version active). */
    Plan update(UUID id, PlanInput in) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        Plan current = get(id);
        if (!current.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "plan_outdated",
                    "Seule la version active d'un plan peut être modifiée");
        }
        PlanInput withCode = new PlanInput(current.code(), in.label(), in.point(), in.target(), in.characteristics(),
                in.sampling(), in.frequency());
        validate(withCode);
        jdbc.sql("update control_plan set active = false where id = :id").param("id", id).update();
        return insert(withCode, current.version() + 1);
    }

    private Plan insert(PlanInput in, int version) {
        UUID id = jdbc.sql("""
                insert into control_plan (tenant_id, code, version, label, point, target_type, target_id,
                  target_family, characteristics, sampling, frequency, created_by)
                values (app_current_tenant(), :code, :v, :label, :point, :tt, :tid, :tf, cast(:ch as jsonb),
                  cast(:s as jsonb), cast(:f as jsonb), app_current_user()) returning id""")
                .param("code", in.code().strip()).param("v", version).param("label", in.label().strip())
                .param("point", in.point()).param("tt", in.target().type()).param("tid", in.target().id())
                .param("tf", LotService.blankToNull(in.target().famille()))
                .param("ch", write(in.characteristics())).param("s", write(in.sampling()))
                .param("f", in.frequency() == null ? null : write(in.frequency()))
                .query(UUID.class).single();
        audit.record("CONTROL_PLAN_SAVED", "control_plan", id.toString(),
                write(Map.of("code", in.code(), "version", version)));
        return get(id);
    }

    private void validate(PlanInput in) {
        if (in.code() == null || in.code().isBlank() || in.code().length() > 50) {
            throw ApiException.badRequest("Code obligatoire (50 caractères maximum)");
        }
        if (in.label() == null || in.label().isBlank() || in.label().length() > 200) {
            throw ApiException.badRequest("Libellé obligatoire (200 caractères maximum)");
        }
        if (!POINTS.contains(in.point() == null ? "" : in.point())) {
            throw ApiException.badRequest("Point de flux inconnu");
        }
        Target t = in.target();
        if (t == null || !TARGETS.contains(t.type() == null ? "" : t.type())) {
            throw ApiException.badRequest("Cible : article, famille, fournisseur ou client");
        }
        if ("famille".equals(t.type()) ? LotService.blankToNull(t.famille()) == null : t.id() == null) {
            throw ApiException.badRequest("Cible incomplète (id, ou famille pour une cible famille)");
        }
        validateCharacteristics(in.characteristics());
        Sampling s = in.sampling();
        if (s == null || !Set.of("fixe", "iso2859").contains(s.mode() == null ? "" : s.mode())) {
            throw ApiException.badRequest("Échantillonnage : mode fixe ou iso2859");
        }
        if ("fixe".equals(s.mode()) && (s.taille() == null || s.taille() < 1 || s.taille() > 10000)) {
            throw ApiException.badRequest("Échantillonnage fixe : taille entre 1 et 10000");
        }
        if ("iso2859".equals(s.mode())) {
            Iso2859.normalizeNqa(s.nqa());
            if (s.niveau() != null && !"II".equals(s.niveau())) {
                throw ApiException.badRequest("Niveau de contrôle : seul le niveau général II est pris en charge");
            }
        }
        Frequency f = in.frequency();
        if (f != null && ((f.pieces() != null && f.pieces() < 1) || (f.minutes() != null && f.minutes() < 1))) {
            throw ApiException.badRequest("Fréquence : valeurs strictement positives");
        }
    }

    private static void validateCharacteristics(List<Characteristic> list) {
        if (list == null || list.isEmpty() || list.size() > 100) {
            throw ApiException.badRequest("Au moins une caractéristique (100 maximum)");
        }
        Set<String> codes = new HashSet<>();
        for (Characteristic c : list) {
            if (c.code() == null || c.code().isBlank() || c.code().length() > 50 || !codes.add(c.code())) {
                throw ApiException.badRequest("Caractéristique : code obligatoire et unique (50 caractères maximum)");
            }
            if (c.libelle() == null || c.libelle().isBlank()) {
                throw ApiException.badRequest("Caractéristique " + c.code() + " : libellé obligatoire");
            }
            if (!TYPES.contains(c.type() == null ? "" : c.type())) {
                throw ApiException.badRequest("Caractéristique " + c.code() + " : type mesure, attribut ou document");
            }
            if (!CRITICITES.contains(c.criticite() == null ? "" : c.criticite())) {
                throw ApiException.badRequest("Caractéristique " + c.code() + " : criticité critique, majeure ou mineure");
            }
            if ("mesure".equals(c.type()) && c.tolMin() != null && c.tolMax() != null && c.tolMin() > c.tolMax()) {
                throw ApiException.badRequest("Caractéristique " + c.code() + " : tolMin > tolMax");
            }
        }
    }

    private Plan map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            String f = rs.getString("frequency");
            return new Plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("version"),
                    rs.getString("label"), rs.getString("point"),
                    new Target(rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                            rs.getString("target_family")),
                    json.readValue(rs.getString("characteristics"), new TypeReference<List<Characteristic>>() { }),
                    json.readValue(rs.getString("sampling"), Sampling.class),
                    f == null ? null : json.readValue(f, Frequency.class),
                    rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
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
