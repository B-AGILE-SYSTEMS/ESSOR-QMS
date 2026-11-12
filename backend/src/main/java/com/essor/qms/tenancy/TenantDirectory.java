package com.essor.qms.tenancy;

import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.ExecutionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Accès au registre des tenants et aux sites du tenant courant. */
@Service
public class TenantDirectory {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    TenantDirectory(JdbcClient jdbc, TransactionTemplate tx, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
    }

    public Optional<TenantRef> findByCode(String code) {
        return jdbc.sql("select id, code, name from tenant where code = :code")
                .param("code", code).query(TenantRef.class).optional();
    }

    public Optional<TenantRef> findById(UUID id) {
        return jdbc.sql("select id, code, name from tenant where id = :id")
                .param("id", id).query(TenantRef.class).optional();
    }

    public List<TenantRef> findAll() {
        return jdbc.sql("select id, code, name from tenant order by code").query(TenantRef.class).list();
    }

    /** Sites du tenant courant (RLS). */
    public List<SiteRef> sitesOfCurrentTenant() {
        return tx.execute(s -> jdbc.sql("select id, code, name from site order by code")
                .query(SiteRef.class).list());
    }

    /**
     * Crée un tenant, son habillage par défaut et son site principal. L'audit est écrit
     * dans le nouveau tenant (la RLS impose que tenant_id = tenant courant).
     */
    public TenantRef create(String code, String name) {
        UUID id = UUID.randomUUID();
        return ExecutionContext.callAs(ExecutionContext.userId(), id, () -> tx.execute(s -> {
            jdbc.sql("insert into tenant (id, code, name) values (:id, :code, :name)")
                    .param("id", id).param("code", code).param("name", name).update();
            jdbc.sql("insert into tenant_branding (tenant_id) values (:id)").param("id", id).update();
            jdbc.sql("insert into site (tenant_id, code, name) values (:id, 'SIEGE', 'Siège')")
                    .param("id", id).update();
            audit.record("TENANT_CREATED", "tenant", id.toString(), null);
            return new TenantRef(id, code, name);
        }));
    }
}
