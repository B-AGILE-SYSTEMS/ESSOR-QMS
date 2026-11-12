package com.essor.qms.records;

import com.essor.qms.records.RecordEvents.DeadlineKind;
import com.essor.qms.records.RecordEvents.RecordDeadline;
import com.essor.qms.records.RecordTypes.RecordType;
import com.essor.qms.shared.ExecutionContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tâche planifiée quotidienne : publie {@link RecordDeadline} pour les fiches non réalisées en retard
 * ou arrivant à échéance dans {@value #DUE_SOON_DAYS} jours. Un passage par tenant, chacun dans son contexte RLS.
 */
@Component
class DeadlineJob {

    static final int DUE_SOON_DAYS = 2;

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }

    private record Due(UUID id, String reference, String title, UUID ownerId, LocalDate dueDate) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RecordTypes types;
    private final ApplicationEventPublisher events;

    DeadlineJob(JdbcClient jdbc, TransactionTemplate tx, RecordTypes types, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.types = types;
        this.events = events;
    }

    @Scheduled(cron = "${essor.notifications.deadline-cron:0 0 6 * * *}", zone = "Africa/Casablanca")
    void daily() {
        run(LocalDate.now(RecordService.ZONE));
    }

    /** @return nombre d'événements publiés */
    int run(LocalDate today) {
        // registre des tenants : non tenant-scopé
        List<UUID> tenants = jdbc.sql("select id from tenant order by code").query(UUID.class).list();
        int published = 0;
        for (UUID tenant : tenants) {
            Integer n = ExecutionContext.callAs(null, tenant, () -> tx.execute(s -> runForTenant(today)));
            published += n == null ? 0 : n;
        }
        return published;
    }

    private int runForTenant(LocalDate today) {
        Map<String, RecordType> all = types.all();
        Set<String> done = RecordService.doneStates(all);
        all.values().forEach(t -> done.addAll(t.workflow().finalStates()));
        List<Due> due = jdbc.sql("""
                select id, reference, title, owner_id, due_date from record
                where owner_id is not null and due_date is not null and status <> all(cast(:done as text[]))
                  and (due_date < :today or due_date = :soon)
                """).param("done", done.toArray(String[]::new)).param("today", today)
                .param("soon", today.plusDays(DUE_SOON_DAYS)).query(Due.class).list();
        for (Due d : due) {
            DeadlineKind kind = d.dueDate().isBefore(today) ? DeadlineKind.OVERDUE : DeadlineKind.DUE_SOON;
            events.publishEvent(new RecordDeadline(d.id(), d.reference(), d.title(), d.ownerId(), d.dueDate(), kind));
        }
        return due.size();
    }
}
