package com.essor.qms.regulatory;

import com.essor.qms.records.DailyTask;
import com.essor.qms.records.DashboardKpis;
import com.essor.qms.records.RecordEvents.Alert;
import com.essor.qms.records.RecordEvents.AlertKind;
import com.essor.qms.shared.CurrentUser;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Alertes de réévaluation réglementaire (J-15 et échue, sur la dernière évaluation de chaque texte × site, sans
 * doublon) et KPI « conformité réglementaire » du tableau de bord (taux global).
 */
@Component
class RegulationAlerts implements DailyTask, DashboardKpis {

    static final int ALERT_DAYS = 15;

    private record Due(UUID regulationId, String reference, String siteCode, LocalDate nextDue) {
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final RegulationService service;

    RegulationAlerts(JdbcClient jdbc, ApplicationEventPublisher events, RegulationService service) {
        this.jdbc = jdbc;
        this.events = events;
        this.service = service;
    }

    @Override
    public int run(LocalDate today) {
        List<Due> due = jdbc.sql("""
                select l.regulation_id, r.reference, s.code site_code, l.next_due from (
                  select distinct on (regulation_id, site_id) regulation_id, site_id, next_due
                  from regulation_evaluation order by regulation_id, site_id, evaluated_at desc) l
                join regulation r on r.id = l.regulation_id join site s on s.id = l.site_id
                where r.status = 'en_vigueur' and (l.next_due < :today or l.next_due = :soon)""")
                .param("today", today).param("soon", today.plusDays(ALERT_DAYS)).query(Due.class).list();
        for (Due d : due) {
            boolean late = d.nextDue().isBefore(today);
            events.publishEvent(new Alert(AlertKind.REGULATION_DUE, (late ? "Réévaluation réglementaire échue : "
                    : "Réévaluation réglementaire avant le " + d.nextDue() + " : ") + d.reference() + " ("
                    + d.siteCode() + ")", "/veille/" + d.regulationId(), Set.of(),
                    List.of(CurrentUser.QUALITY_MANAGER),
                    (late ? "REG_LATE:" : "REG_SOON:") + d.regulationId() + ":" + d.siteCode() + ":" + d.nextDue(),
                    null));
        }
        return due.size();
    }

    @Override
    public Map<String, Object> kpis() {
        Map<String, Object> m = new HashMap<>();
        m.put("regulatoryCompliance", service.compliance(null).get("rate"));
        return m;
    }
}
