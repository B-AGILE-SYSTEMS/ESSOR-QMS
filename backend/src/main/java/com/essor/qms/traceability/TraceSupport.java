package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Utilitaires communs du module : rôles de l'utilisateur courant, contrôles d'accès, références. */
@Component
class TraceSupport {

    static final String QUALITY_MANAGER = "QUALITY_MANAGER";

    private final JdbcClient jdbc;

    TraceSupport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rôles mémorisés à la connexion (app_user.roles) ; à appeler dans une transaction. */
    Set<String> roles() {
        return jdbc.sql("select roles from app_user where id = app_current_user()")
                .query((rs, i) -> toSet(rs.getArray(1))).optional().orElse(Set.of());
    }

    void requireWriter() {
        Set<String> r = roles();
        if (r.isEmpty() || r.equals(Set.of("READER"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Action réservée aux utilisateurs non lecteurs");
        }
    }

    void requireAny(String... allowed) {
        Set<String> r = roles();
        if (Arrays.stream(allowed).noneMatch(r::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Rôle requis : " + String.join(" ou ", allowed));
        }
    }

    boolean siteExists(UUID siteId) {
        return jdbc.sql("select count(*) from site where id = :id").param("id", siteId).query(Long.class).single() > 0;
    }

    /** {@code {id, code, name}} d'un partenaire, ou null. */
    Map<String, Object> partnerRef(UUID id) {
        if (id == null) {
            return null;
        }
        return jdbc.sql("select id, code, name, type from partner where id = :id").param("id", id)
                .query((rs, i) -> Map.<String, Object>of("id", rs.getObject("id"), "code", rs.getString("code"),
                        "name", rs.getString("name"), "type", rs.getString("type")))
                .optional().orElse(null);
    }

    private static Set<String> toSet(Array a) throws SQLException {
        return a == null ? Set.of() : Set.of((String[]) a.getArray());
    }
}
