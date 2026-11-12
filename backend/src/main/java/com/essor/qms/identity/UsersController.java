package com.essor.qms.identity;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/users?q=} : utilisateurs du tenant courant (RLS), pour choisir un responsable. */
@RestController
@Validated
class UsersController {

    private static final int MAX_RESULTS = 50;

    record UserRef(UUID id, String username, String displayName) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    UsersController(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @GetMapping("/api/users")
    List<UserRef> search(@RequestParam(required = false) @Size(max = 100) String q) {
        String pattern = q == null || q.isBlank() ? "%" : "%" + escapeLike(q.trim().toLowerCase()) + "%";
        return tx.execute(s -> jdbc.sql("""
                select id, username, coalesce(display_name, username) as display_name from app_user
                where lower(username) like :p escape '\\' or lower(coalesce(display_name, '')) like :p escape '\\'
                order by display_name, username limit :max
                """).param("p", pattern).param("max", MAX_RESULTS).query(UserRef.class).list());
    }

    private static String escapeLike(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
