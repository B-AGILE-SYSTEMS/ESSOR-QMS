package com.essor.qms.notifications;

import com.essor.qms.records.RecordEvents.DeadlineKind;
import com.essor.qms.records.RecordEvents.RecordAssigned;
import com.essor.qms.records.RecordEvents.RecordDeadline;
import com.essor.qms.records.RecordEvents.RecordTransitioned;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transforme les événements du moteur de fiches en notifications (in-app, temps réel SSE, e-mail),
 * selon les règles du tenant. S'exécute dans la transaction de l'événement (même tenant, RLS).
 */
@Service
class NotificationService {

    enum EventType { ASSIGNED, TRANSITION, OVERDUE, DUE_SOON }

    enum Channel { IN_APP, EMAIL }

    record Notification(UUID id, String type, String title, UUID recordId, String link, boolean read,
                        OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final NotificationRules rules;
    private final SseHub sse;
    private final MailNotifier mail;

    NotificationService(JdbcClient jdbc, NotificationRules rules, SseHub sse, MailNotifier mail) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.sse = sse;
        this.mail = mail;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onAssigned(RecordAssigned e) {
        if (e.ownerId() != null && !e.ownerId().equals(e.actorId())) {
            notify(Set.of(e.ownerId()), EventType.ASSIGNED, e.reference() + " vous est assignée : " + e.title(),
                    e.recordId(), null);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onTransition(RecordTransitioned e) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (e.ownerId() != null) {
            recipients.add(e.ownerId());
        }
        if (!e.nextRoles().isEmpty()) {
            recipients.addAll(jdbc.sql("select id from app_user where roles && cast(:roles as text[])")
                    .param("roles", e.nextRoles().toArray(String[]::new)).query(UUID.class).list());
        }
        recipients.remove(e.actorId());
        notify(recipients, EventType.TRANSITION,
                e.reference() + " : " + e.transitionLabel() + " → " + e.statusLabel(), e.recordId(), null);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onDeadline(RecordDeadline e) {
        EventType type = e.kind() == DeadlineKind.OVERDUE ? EventType.OVERDUE : EventType.DUE_SOON;
        String title = type == EventType.OVERDUE
                ? e.reference() + " en retard (échéance " + e.dueDate() + ") : " + e.title()
                : e.reference() + " arrive à échéance le " + e.dueDate() + " : " + e.title();
        notify(Set.of(e.ownerId()), type, title, e.recordId(), type + ":" + e.recordId() + ":" + e.dueDate());
    }

    private void notify(Set<UUID> recipients, EventType type, String title, UUID recordId, String dedupKey) {
        if (recipients.isEmpty()) {
            return;
        }
        String shortTitle = title.length() > 300 ? title.substring(0, 297) + "..." : title;
        if (rules.enabled(type, Channel.IN_APP)) {
            for (UUID r : recipients) {
                insert(r, type, shortTitle, recordId, dedupKey).ifPresent(n -> afterCommit(() -> sse.send(r, n)));
            }
        }
        if (rules.enabled(type, Channel.EMAIL) && mail.isEnabled()) {
            List<String> emails = jdbc.sql("select email from app_user where id = any(:ids) and email is not null")
                    .param("ids", recipients.toArray(UUID[]::new)).query(String.class).list();
            afterCommit(() -> emails.stream().filter(Objects::nonNull).forEach(m -> mail.send(m, shortTitle)));
        }
    }

    private java.util.Optional<Notification> insert(UUID recipient, EventType type, String title, UUID recordId,
                                                    String dedupKey) {
        return jdbc.sql("""
                insert into notification (tenant_id, recipient_id, type, title, record_id, link, dedup_key)
                values (app_current_tenant(), :r, :type, :title, :rec, :link, :dedup)
                on conflict (tenant_id, recipient_id, dedup_key) do nothing
                returning id, type, title, record_id, link, read, created_at
                """)
                .param("r", recipient).param("type", type.name()).param("title", title).param("rec", recordId)
                .param("link", recordId == null ? null : "/records/" + recordId).param("dedup", dedupKey)
                .query(Notification.class).optional();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
