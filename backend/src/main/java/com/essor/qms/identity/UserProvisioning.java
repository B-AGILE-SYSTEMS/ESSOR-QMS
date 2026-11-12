package com.essor.qms.identity;

import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.ExecutionContext;
import com.essor.qms.tenancy.TenantDirectory;
import com.essor.qms.tenancy.TenantRef;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provisionnement « juste à temps » : à la première requête authentifiée, l'utilisateur
 * Keycloak est créé dans {@code app_user}, dans le tenant donné par l'attribut Keycloak
 * {@code tenant} (code), à défaut le tenant par défaut, et rattaché à tous les sites du tenant.
 */
@Service
class UserProvisioning {

    record ProvisionedUser(UUID userId, TenantRef tenant) {
    }

    static class ProvisioningRefusedException extends RuntimeException {
        ProvisioningRefusedException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantDirectory tenants;
    private final AuditLog audit;
    private final String defaultTenantCode;

    UserProvisioning(JdbcClient jdbc, TransactionTemplate tx, TenantDirectory tenants, AuditLog audit,
                     @Value("${essor.tenancy.default-tenant-code:}") String defaultTenantCode) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.audit = audit;
        this.defaultTenantCode = defaultTenantCode;
    }

    ProvisionedUser provision(PrincipalInfo p) {
        String code = p.tenantCode() != null ? p.tenantCode() : defaultTenantCode;
        TenantRef tenant = Optional.ofNullable(code).filter(c -> !c.isBlank())
                .flatMap(tenants::findByCode)
                .orElseThrow(() -> new ProvisioningRefusedException("Aucun tenant pour cet utilisateur"));

        UUID userId = ExecutionContext.callAs(null, tenant.id(), () -> tx.execute(s -> {
            Optional<UUID> existing = jdbc.sql("select id from app_user where subject = :sub")
                    .param("sub", p.subject()).query(UUID.class).optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into app_user (id, tenant_id, subject, username, display_name, last_login_at)
                    values (:id, app_current_tenant(), :sub, :username, :name, now())
                    """)
                    .param("id", id).param("sub", p.subject())
                    .param("username", p.username()).param("name", p.displayName())
                    .update();
            jdbc.sql("""
                    insert into user_site (tenant_id, user_id, site_id)
                    select tenant_id, :id, id from site
                    """).param("id", id).update();
            // l'acteur de l'audit est l'utilisateur qui vient d'être créé
            jdbc.sql("select set_config('app.user_id', :id, true)").param("id", id.toString())
                    .query(String.class).single();
            audit.record("USER_PROVISIONED", "app_user", id.toString(), null);
            return id;
        }));
        return new ProvisionedUser(userId, tenant);
    }
}
