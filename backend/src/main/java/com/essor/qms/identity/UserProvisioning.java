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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UserProvisioning.class);

    record ProvisionedUser(UUID userId, TenantRef tenant) {
    }

    private record Lookup(UUID userId, UUID tenantId) {
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

    static final String PENDING_PREFIX = "pending:";

    /** Mémorise les rôles realm et l'e-mail (utilisés par le workflow et les notifications), sans écriture inutile. */
    private void refresh(UUID userId, PrincipalInfo p) {
        jdbc.sql("""
                update app_user set roles = cast(:roles as text[]), email = :email, display_name = :name
                where id = :id and (roles is distinct from cast(:roles as text[]) or email is distinct from :email
                                    or display_name is distinct from :name)
                """)
                .param("roles", p.roles().toArray(String[]::new)).param("email", p.email())
                .param("name", p.displayName()).param("id", userId).update();
    }

    ProvisionedUser provision(PrincipalInfo p) {
        String code = p.tenantCode() != null ? p.tenantCode() : defaultTenantCode;
        TenantRef tenant = Optional.ofNullable(code).filter(c -> !c.isBlank())
                .flatMap(tenants::findByCode)
                .orElseThrow(() -> new ProvisioningRefusedException("Aucun tenant pour cet utilisateur"));

        UUID userId = ExecutionContext.callAs(null, tenant.id(), () -> tx.execute(s -> {
            // Recherche tous tenants confondus (fonction SECURITY DEFINER limitée), pour détecter
            // un changement d'attribut "tenant" dans Keycloak.
            Optional<Lookup> existing = jdbc.sql("select user_id, tenant_id from app_user_lookup(:sub)")
                    .param("sub", p.subject()).query(Lookup.class).optional();
            if (existing.isPresent()) {
                if (!existing.get().tenantId().equals(tenant.id())) {
                    // aucune donnée personnelle : identifiant technique uniquement
                    LOG.warn("Changement de tenant refusé pour l'utilisateur {}", existing.get().userId());
                    throw new ProvisioningRefusedException("Changement de tenant refusé");
                }
                refresh(existing.get().userId(), p);
                return existing.get().userId();
            }
            // Compte préparé (données de démo) : "pending:<username>" est rattaché au premier login.
            Optional<UUID> pending = jdbc.sql("select id from app_user where subject = :pending")
                    .param("pending", PENDING_PREFIX + p.username()).query(UUID.class).optional();
            if (pending.isPresent()) {
                jdbc.sql("update app_user set subject = :sub where id = :id")
                        .param("sub", p.subject()).param("id", pending.get()).update();
                refresh(pending.get(), p);
                return pending.get();
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into app_user (id, tenant_id, subject, username, display_name, last_login_at, roles, email)
                    values (:id, app_current_tenant(), :sub, :username, :name, now(), cast(:roles as text[]), :email)
                    """)
                    .param("roles", p.roles().toArray(String[]::new)).param("email", p.email())
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
