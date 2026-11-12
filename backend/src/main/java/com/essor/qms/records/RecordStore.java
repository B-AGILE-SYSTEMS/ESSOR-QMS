package com.essor.qms.records;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Accès SQL aux fiches du tenant courant. Toutes les méthodes s'exécutent dans une transaction (RLS). */
@Component
class RecordStore {

    static final String SELECT = """
            select r.id, r.type_code, r.reference, r.status, r.title, r.description, r.severity,
                   r.site_id, s.name as site_name, r.owner_id, coalesce(o.display_name, o.username) as owner_name,
                   r.due_date, r.parent_id, r.data::text as data, r.created_at, r.created_by,
                   coalesce(c.display_name, c.username) as created_by_name,
                   r.client_id, r.occurred_at, r.updated_at
            from record r
            left join site s on s.id = r.site_id
            left join app_user o on o.id = r.owner_id
            left join app_user c on c.id = r.created_by
            """;

    record Row(UUID id, String typeCode, String reference, String status, String title, String description,
               String severity, UUID siteId, String siteName, UUID ownerId, String ownerName, LocalDate dueDate,
               UUID parentId, String data, OffsetDateTime createdAt, UUID createdBy, String createdByName,
               UUID clientId, OffsetDateTime occurredAt, OffsetDateTime updatedAt) {
    }

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RecordStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Row> find(UUID id, boolean forUpdate) {
        if (forUpdate) {
            // verrou de la ligne (transitions concurrentes)
            jdbc.sql("select id from record where id = :id for update").param("id", id).query(UUID.class).optional();
        }
        return jdbc.sql(SELECT + " where r.id = :id").param("id", id).query(Row.class).optional();
    }

    Optional<UUID> findByClientId(UUID clientId) {
        return jdbc.sql("select id from record where client_id = :c").param("c", clientId).query(UUID.class)
                .optional();
    }

    List<Row> children(UUID parentId) {
        return jdbc.sql(SELECT + " where r.parent_id = :p order by r.created_at, r.reference")
                .param("p", parentId).query(Row.class).list();
    }

    List<Row> query(String where, Map<String, Object> params, String orderBy, int limit, long offset) {
        return jdbc.sql(SELECT + " where " + where + " order by " + orderBy + " limit :limit offset :offset")
                .params(params).param("limit", limit).param("offset", offset).query(Row.class).list();
    }

    long count(String where, Map<String, Object> params) {
        return jdbc.sql("select count(*) from record r where " + where).params(params).query(Long.class).single();
    }

    /** Rôles realm mémorisés de l'utilisateur courant. */
    Set<String> currentRoles() {
        return jdbc.sql("select roles from app_user where id = app_current_user()")
                .query((rs, i) -> toSet(rs.getArray(1))).optional().orElse(Set.of());
    }

    boolean userExists(UUID userId) {
        return jdbc.sql("select count(*) from app_user where id = :id").param("id", userId)
                .query(Long.class).single() > 0;
    }

    /** Le site existe dans le tenant et l'utilisateur courant y est rattaché. */
    boolean currentUserHasSite(UUID siteId) {
        return jdbc.sql("select count(*) from user_site where site_id = :s and user_id = app_current_user()")
                .param("s", siteId).query(Long.class).single() > 0;
    }

    String nextReference(String prefix, int year) {
        int n = jdbc.sql("""
                insert into record_sequence (tenant_id, prefix, year, last_value)
                values (app_current_tenant(), :p, :y, 1)
                on conflict (tenant_id, prefix, year) do update set last_value = record_sequence.last_value + 1
                returning last_value
                """).param("p", prefix).param("y", year).query(Integer.class).single();
        return "%s-%d-%04d".formatted(prefix, year, n);
    }

    Map<String, Object> data(Row row) {
        return parse(row.data());
    }

    Map<String, Object> parse(String text) {
        try {
            return text == null ? new LinkedHashMap<>() : json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Données de fiche illisibles", e);
        }
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    private static Set<String> toSet(Array array) throws java.sql.SQLException {
        if (array == null) {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList((String[]) array.getArray()));
    }
}
