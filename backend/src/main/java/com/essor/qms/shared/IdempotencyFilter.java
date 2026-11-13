package com.essor.qms.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * En-tête {@code Idempotency-Key} (UUID) sur un POST ou un PUT de l'API (ex. réponses d'audit mobiles) : la réponse est gardée 48 h par (tenant, clé).
 * Un renvoi rend la même réponse (statut et corps) sans réexécuter la requête. Clé réutilisée pour une autre
 * requête ou encore en cours de traitement : 409. Les erreurs 5xx ne sont pas mémorisées (nouvel essai possible).
 * S'exécute après {@code ExecutionContextFilter} (tenant connu, RLS).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MAX_REQUEST = 300;

    private record Stored(String request, Integer status, String contentType, String body) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    IdempotencyFilter(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ExecutionContext.tenantId() == null) {
            chain.doFilter(request, response); // non authentifié : la sécurité répond
            return;
        }
        UUID key;
        try {
            key = UUID.fromString(request.getHeader(HEADER).strip());
        } catch (IllegalArgumentException e) {
            error(response, 400, "invalid", "Idempotency-Key doit être un UUID");
            return;
        }
        String fingerprint = truncate(request.getMethod() + " " + request.getRequestURI());
        Optional<Stored> previous = reserve(key, fingerprint);
        if (previous.isPresent()) {
            replay(previous.get(), fingerprint, response);
            return;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status < 500) {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                tx.executeWithoutResult(s -> jdbc.sql("""
                        update idempotency_key set status = :st, content_type = :ct, body = :b where key = :k
                        """).param("st", status).param("ct", wrapper.getContentType()).param("b", body)
                        .param("k", key).update());
                stored = true;
            }
        } finally {
            if (!stored) {
                tx.executeWithoutResult(s -> jdbc.sql("delete from idempotency_key where key = :k and status is null")
                        .param("k", key).update());
            }
            wrapper.copyBodyToResponse();
        }
    }

    /** Réserve la clé ; rend l'enregistrement existant si elle est déjà connue (moins de 48 h). */
    private Optional<Stored> reserve(UUID key, String fingerprint) {
        return tx.execute(s -> {
            jdbc.sql("delete from idempotency_key where created_at < now() - interval '48 hours'").update();
            int inserted = jdbc.sql("""
                    insert into idempotency_key (tenant_id, key, user_id, request)
                    values (app_current_tenant(), :k, app_current_user(), :r)
                    on conflict do nothing
                    """).param("k", key).param("r", fingerprint).update();
            if (inserted == 1) {
                return Optional.<Stored>empty();
            }
            return jdbc.sql("select request, status, content_type, body from idempotency_key where key = :k")
                    .param("k", key).query(Stored.class).optional();
        });
    }

    private void replay(Stored s, String fingerprint, HttpServletResponse response) throws IOException {
        if (!s.request().equals(fingerprint)) {
            error(response, 409, "idempotency_key_reused", "Idempotency-Key déjà utilisée pour une autre requête");
            return;
        }
        if (s.status() == null) {
            error(response, 409, "idempotency_in_progress", "Requête identique en cours de traitement");
            return;
        }
        response.setStatus(s.status());
        response.setHeader("Idempotent-Replayed", "true");
        if (s.contentType() != null) {
            response.setContentType(s.contentType());
        }
        if (s.body() != null && !s.body().isEmpty()) {
            response.getOutputStream().write(s.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(
                Map.of("error", code, "message", message, "details", Map.of())));
    }

    private static String truncate(String s) {
        return s.length() > MAX_REQUEST ? s.substring(0, MAX_REQUEST) : s;
    }
}
