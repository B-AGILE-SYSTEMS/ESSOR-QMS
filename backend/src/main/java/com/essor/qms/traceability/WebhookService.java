package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.ExecutionContext;
import com.essor.qms.shared.SecretCipher;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connecteur webhook sortant (un par tenant). Chaque événement est journalisé ({@code webhook_delivery}) dans la
 * transaction métier, puis envoyé après validation, en tâche de fond : 3 essais au plus (attente croissante),
 * repris au démarrage puis toutes les minutes tant qu'il reste des essais ; secret chiffré en base (AES-GCM) ;
 * corps JSON signé HMAC-SHA256 ({@code X-Essor-Signature: sha256=<hex>}).
 */
@Service
class WebhookService {

    static final int MAX_ATTEMPTS = 3;
    static final String SIGNATURE_HEADER = "X-Essor-Signature";
    private static final Logger LOG = LoggerFactory.getLogger(WebhookService.class);

    record Config(String url, boolean secretSet, boolean enabled, Instant updatedAt) {
    }

    record ConfigInput(String url, String secret, Boolean enabled) {
    }

    record Delivery(UUID id, String event, String url, String payload, String status, int attempts, Integer lastCode,
                    String lastError, Instant createdAt, Instant lastAttemptAt) {
    }

    record Queued(UUID tenantId, UUID deliveryId) {
    }

    private record Target(String url, String secret, String payload, String event) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final TraceSupport support;
    private final AuditLog audit;
    private final SecretCipher cipher;
    private final long backoffMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "essor-webhook");
        t.setDaemon(true);
        return t;
    });

    WebhookService(JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher events, TraceSupport support,
                   AuditLog audit, SecretCipher cipher, @Value("${essor.webhook.backoff-ms:2000}") long backoffMs) {
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.support = support;
        this.audit = audit;
        this.backoffMs = backoffMs;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    @Transactional(readOnly = true)
    public Config config() {
        support.requireAny("ADMIN");
        return jdbc.sql("select url, enabled, updated_at from webhook_config")
                .query((rs, i) -> new Config(rs.getString("url"), true, rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional().orElse(new Config(null, false, false, null));
    }

    @Transactional
    public Config save(ConfigInput in) {
        support.requireAny("ADMIN");
        String url = in.url() == null ? "" : in.url().strip();
        if (!url.matches("^https?://[^\\s]{1,490}$")) {
            throw ApiException.badRequest("URL http(s) attendue");
        }
        boolean exists = jdbc.sql("select count(*) from webhook_config").query(Long.class).single() > 0;
        String secret = in.secret() == null ? null : in.secret().strip();
        if (secret != null && !secret.isEmpty() && (secret.length() < 16 || secret.length() > 200)) {
            throw ApiException.badRequest("Secret HMAC : 16 à 200 caractères");
        }
        boolean enabled = in.enabled() == null || in.enabled();
        if (!exists) {
            if (secret == null || secret.isEmpty()) {
                throw ApiException.badRequest("Secret HMAC obligatoire à la création");
            }
            jdbc.sql("insert into webhook_config (tenant_id, url, secret, enabled) "
                            + "values (app_current_tenant(), :url, :secret, :enabled)")
                    .param("url", url).param("secret", cipher.encrypt(secret)).param("enabled", enabled).update();
        } else {
            jdbc.sql("""
                    update webhook_config set url = :url, enabled = :enabled, updated_at = now(),
                      secret = case when :secret = '' then secret else :secret end""")
                    .param("url", url).param("enabled", enabled).param("secret", secret == null || secret.isEmpty() ? "" : cipher.encrypt(secret))
                    .update();
        }
        audit.record("WEBHOOK_CONFIGURED", "webhook_config", null, "{\"enabled\":" + enabled + "}");
        return config();
    }

    @Transactional(readOnly = true)
    public List<Delivery> deliveries(int limit) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        return jdbc.sql("""
                select id, event, url, payload, status, attempts, last_code, last_error, created_at, last_attempt_at
                from webhook_delivery order by created_at desc limit :limit""")
                .param("limit", Math.max(1, Math.min(limit, 200)))
                .query((rs, i) -> new Delivery(rs.getObject("id", UUID.class), rs.getString("event"),
                        rs.getString("url"), rs.getString("payload"), rs.getString("status"), rs.getInt("attempts"),
                        (Integer) rs.getObject("last_code"), rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_attempt_at") == null ? null
                                : rs.getTimestamp("last_attempt_at").toInstant()))
                .list();
    }

    /** Journalise l'événement si un connecteur actif existe ; l'envoi part après la validation de la transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String event, String payload) {
        List<String> url = jdbc.sql("select url from webhook_config where enabled").query(String.class).list();
        if (url.isEmpty()) {
            return;
        }
        UUID id = jdbc.sql("insert into webhook_delivery (tenant_id, event, url, payload) "
                        + "values (app_current_tenant(), :event, :url, :payload) returning id")
                .param("event", event).param("url", url.get(0)).param("payload", payload)
                .query(UUID.class).single();
        events.publishEvent(new Queued(ExecutionContext.tenantId(), id));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterCommit(Queued q) {
        executor.submit(() -> deliver(q));
    }

    /** Envoi après validation : essais successifs avec attente croissante ; sinon repris par {@link #retryDue}. */
    void deliver(Queued q) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Boolean done = attemptOnce(q, 1.0);
            if (done == null || done || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (!sleep(backoffMs * attempt)) {
                return;
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        executor.submit(this::retryDue);
    }

    @Scheduled(initialDelayString = "${essor.webhook.retry-ms:60000}",
            fixedDelayString = "${essor.webhook.retry-ms:60000}")
    void scheduledRetry() {
        retryDue();
    }

    /** Renvoie les envois PENDING échus (jamais tentés ou en échec avec essais restants). @return nombre traité */
    int retryDue() {
        List<UUID> tenants = jdbc.sql("select id from tenant order by code").query(UUID.class).list();
        int n = 0;
        for (UUID tenant : tenants) {
            List<UUID> due = ExecutionContext.callAs(null, tenant, () -> tx.execute(s -> jdbc.sql("""
                    select id from webhook_delivery where status = 'PENDING' and attempts < :max
                      and next_attempt_at <= now() order by created_at limit 100""")
                    .param("max", MAX_ATTEMPTS).query(UUID.class).list()));
            for (UUID id : due == null ? List.<UUID>of() : due) {
                if (attemptOnce(new Queued(tenant, id), 0) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    private record Claimed(Target target, int attempts) {
    }

    /**
     * Un essai : réserve l'envoi (bail de 5 min contre un envoi concurrent), poste, enregistre le résultat.
     * @return true envoyé, false en échec, null rien à faire (déjà traité, réservé ailleurs ou pas encore échu)
     */
    private Boolean attemptOnce(Queued q, double toleranceSecs) {
        Claimed claimed = ExecutionContext.callAs(null, q.tenantId(), () -> tx.execute(s -> jdbc.sql("""
                        update webhook_delivery d set next_attempt_at = now() + interval '5 minutes'
                        from webhook_config c
                        where d.id = :id and c.tenant_id = d.tenant_id and d.status = 'PENDING'
                          and d.attempts < :max and d.next_attempt_at <= now() + make_interval(secs => :tol)
                        returning d.url, c.secret, d.payload, d.event, d.attempts""")
                .param("id", q.deliveryId()).param("max", MAX_ATTEMPTS).param("tol", toleranceSecs)
                .query((rs, i) -> new Claimed(new Target(rs.getString(1), cipher.decrypt(rs.getString(2)),
                        rs.getString(3), rs.getString(4)), rs.getInt(5)))
                .optional().orElse(null)));
        if (claimed == null) {
            return null;
        }
        Target target = claimed.target();
        int attempt = claimed.attempts() + 1;
        Integer code = null;
        String error = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Essor-Event", target.event())
                    .header("X-Essor-Delivery", q.deliveryId().toString())
                    .header(SIGNATURE_HEADER, "sha256=" + sign(target.secret(), target.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(target.payload(), StandardCharsets.UTF_8))
                    .build();
            code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code < 200 || code >= 300) {
                error = "Réponse HTTP " + code;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Envoi interrompu";
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        boolean ok = error == null;
        String status = ok ? "SENT" : (attempt >= MAX_ATTEMPTS ? "FAILED" : "PENDING");
        record(q, attempt, code, error, status, backoffMs * attempt);
        if (!ok && attempt >= MAX_ATTEMPTS) {
            LOG.warn("Webhook {} en échec après {} essais", q.deliveryId(), MAX_ATTEMPTS);
        }
        return ok;
    }

    private void record(Queued q, int attempt, Integer code, String error, String status, long delayMs) {
        ExecutionContext.callAs(null, q.tenantId(), () -> tx.execute(s -> jdbc.sql("""
                        update webhook_delivery set attempts = :a, last_code = :code, last_error = :err,
                          status = :status, last_attempt_at = now(),
                          next_attempt_at = now() + make_interval(secs => :delay) where id = :id""")
                .param("a", attempt).param("code", code).param("err", error).param("status", status)
                .param("delay", delayMs / 1000.0).param("id", q.deliveryId()).update()));
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
