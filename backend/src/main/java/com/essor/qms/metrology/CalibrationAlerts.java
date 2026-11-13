package com.essor.qms.metrology;

import com.essor.qms.records.DailyTask;
import com.essor.qms.records.DashboardKpis;
import com.essor.qms.records.RecordEvents.Alert;
import com.essor.qms.records.RecordEvents.AlertKind;
import com.essor.qms.shared.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Alertes d'échéance d'étalonnage (J-15 et échu, tâche quotidienne M1, sans doublon) et KPI du tableau de bord
 * « étalonnages sous 15 jours ». Destinataires : responsables qualité.
 */
@Component
class CalibrationAlerts implements DailyTask, DashboardKpis {

    private record Due(UUID id, String code, String label, LocalDate nextDue) {
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    CalibrationAlerts(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    public int run(LocalDate today) {
        List<Due> due = jdbc.sql("""
                select id, code, label, next_due from instrument
                where status = 'en_service' and next_due is not null and (next_due < :today or next_due = :soon)""")
                .param("today", today).param("soon", today.plusDays(MetrologyService.DUE_SOON_DAYS))
                .query(Due.class).list();
        for (Due d : due) {
            boolean overdue = d.nextDue().isBefore(today);
            String title = overdue ? "Étalonnage échu (" + d.nextDue() + ") : " + d.code() + " " + d.label()
                    : "Étalonnage à faire avant le " + d.nextDue() + " : " + d.code() + " " + d.label();
            events.publishEvent(new Alert(AlertKind.CALIBRATION_DUE, title, "/ressources/instruments/" + d.id(),
                    Set.of(), List.of(CurrentUser.QUALITY_MANAGER),
                    (overdue ? "CAL_OVERDUE:" : "CAL_SOON:") + d.id() + ":" + d.nextDue(), null));
        }
        return due.size();
    }

    @Override
    public Map<String, Object> kpis() {
        LocalDate today = MetrologyService.today();
        long soon = jdbc.sql("""
                select count(*) from instrument where status = 'en_service' and next_due between :t and :s""")
                .param("t", today).param("s", today.plusDays(MetrologyService.DUE_SOON_DAYS)).query(Long.class)
                .single();
        long overdue = jdbc.sql("select count(*) from instrument where status = 'en_service' and next_due < :t")
                .param("t", today).query(Long.class).single();
        return Map.of("calibrationsDue15", soon, "calibrationsOverdue", overdue);
    }
}
