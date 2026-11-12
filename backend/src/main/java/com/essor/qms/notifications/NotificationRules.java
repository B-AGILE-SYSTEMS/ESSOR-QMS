package com.essor.qms.notifications;

import com.essor.qms.notifications.NotificationService.Channel;
import com.essor.qms.notifications.NotificationService.EventType;
import com.essor.qms.shared.AuditLog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Règles événement × canal du tenant. Sans règle : in-app activé, e-mail désactivé. */
@Component
class NotificationRules {

    record Rule(EventType event, Channel channel, boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    NotificationRules(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    static boolean byDefault(Channel channel) {
        return channel == Channel.IN_APP;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    boolean enabled(EventType event, Channel channel) {
        return jdbc.sql("select enabled from notification_rule where event_type = :e and channel = :c")
                .param("e", event.name()).param("c", channel.name()).query(Boolean.class).optional()
                .orElse(byDefault(channel));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Rule> all() {
        List<Rule> rules = new ArrayList<>();
        for (EventType e : EventType.values()) {
            for (Channel c : Channel.values()) {
                rules.add(new Rule(e, c, enabled(e, c)));
            }
        }
        return rules;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Rule> replace(List<Rule> rules) {
        for (Rule r : rules) {
            jdbc.sql("""
                    insert into notification_rule (tenant_id, event_type, channel, enabled)
                    values (app_current_tenant(), :e, :c, :on)
                    on conflict (tenant_id, event_type, channel) do update set enabled = excluded.enabled
                    """).param("e", r.event().name()).param("c", r.channel().name()).param("on", r.enabled())
                    .update();
        }
        audit.record("NOTIFICATION_RULES_UPDATED", "notification_rule", null, null);
        return all();
    }
}
