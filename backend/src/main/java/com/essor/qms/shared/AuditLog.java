package com.essor.qms.shared;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Journal d'audit en ajout seul, dans le tenant courant (RLS).
 * Ne jamais y écrire de donnée personnelle en clair dans {@code detailsJson} :
 * on référence les identifiants techniques.
 */
@Service
public class AuditLog {

    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, String entityId, String detailsJson) {
        jdbc.sql("""
                insert into audit_log (tenant_id, actor_id, action, entity_type, entity_id, details)
                values (app_current_tenant(), app_current_user(), :action, :type, :id, cast(:details as jsonb))
                """)
                .param("action", action)
                .param("type", entityType)
                .param("id", entityId)
                .param("details", detailsJson)
                .update();
    }
}
