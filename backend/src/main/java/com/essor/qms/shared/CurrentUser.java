package com.essor.qms.shared;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Rôles de l'utilisateur courant (app_user.roles, mémorisés à la connexion) et contrôles d'accès des modules M4. */
@Component
public class CurrentUser {

    public static final String QUALITY_MANAGER = "QUALITY_MANAGER";
    public static final String ADMIN = "ADMIN";

    private final JdbcClient jdbc;

    CurrentUser(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** À appeler dans une transaction. */
    public Set<String> roles() {
        return jdbc.sql("select roles from app_user where id = app_current_user()")
                .query((rs, i) -> toSet(rs.getArray(1))).optional().orElse(Set.of());
    }

    public void requireWriter() {
        Set<String> r = roles();
        if (r.isEmpty() || r.equals(Set.of("READER"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Action réservée aux utilisateurs non lecteurs");
        }
    }

    public void requireAny(String... allowed) {
        Set<String> r = roles();
        if (Arrays.stream(allowed).noneMatch(r::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Rôle requis : " + String.join(" ou ", allowed));
        }
    }

    /** ADMIN ou QUALITY_MANAGER : paramétrage, imports, actions de masse. */
    public void requireManager() {
        requireAny(ADMIN, QUALITY_MANAGER);
    }

    private static Set<String> toSet(Array a) throws SQLException {
        return a == null ? Set.of() : Set.of((String[]) a.getArray());
    }
}
