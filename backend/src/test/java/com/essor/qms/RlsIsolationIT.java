package com.essor.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.essor.qms.shared.ExecutionContext;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Preuve que la RLS isole les tenants, via le rôle applicatif réel (essor_app). */
class RlsIsolationIT extends AbstractIntegrationTest {

    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeAll
    static void seed(@Autowired JdbcClient ignored) throws Exception {
        // jdbc injecté uniquement pour garantir que Flyway a migré avant l'insertion (contexte démarré).
        try (Connection c = ownerConnection(); Statement s = c.createStatement()) {
            s.execute("insert into tenant (id, code, name) values ('" + A + "', 'RLS-A', 'A'), ('" + B
                    + "', 'RLS-B', 'B') on conflict do nothing");
            s.execute("insert into site (tenant_id, code, name) values ('" + A + "', 'A1', 'Site A1'), ('" + B
                    + "', 'B1', 'Site B1') on conflict do nothing");
        }
    }

    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> action) {
        return ExecutionContext.callAs(null, tenant, () -> tx.execute(s -> action.get()));
    }

    private List<String> siteCodes() {
        return jdbc.sql("select code from site where code in ('A1','B1') order by code").query(String.class).list();
    }

    @Test
    void connexionApplicativeNonProprietaireEtSansContournementRls() {
        record Role(String usename, boolean rolsuper, boolean rolbypassrls) {
        }
        Role role = jdbc.sql("""
                select current_user as usename, r.rolsuper, r.rolbypassrls
                from pg_roles r where r.rolname = current_user
                """).query(Role.class).single();
        assertThat(role.usename()).isEqualTo("essor_app");
        assertThat(role.rolsuper()).isFalse();
        assertThat(role.rolbypassrls()).isFalse();
    }

    @Test
    void unTenantNeVoitQueSesDonnees() {
        assertThat(inTenant(A, this::siteCodes)).containsExactly("A1");
        assertThat(inTenant(B, this::siteCodes)).containsExactly("B1");
    }

    @Test
    void sansContexteAucuneLigneVisible() {
        assertThat(inTenant(null, this::siteCodes)).isEmpty();
        // hors transaction : aucune variable positionnée non plus
        assertThat(siteCodes()).isEmpty();
    }

    @Test
    void leContexteNeFuitPasEntreTransactions() {
        inTenant(A, this::siteCodes);
        String leaked = inTenant(null, () -> jdbc.sql("select coalesce(current_setting('app.tenant_id', true), '')")
                .query(String.class).single());
        assertThat(leaked).isEmpty();
    }

    @Test
    void impossibleDEcrireDansUnAutreTenant() {
        assertThatThrownBy(() -> inTenant(A, () -> jdbc.sql(
                "insert into site (tenant_id, code, name) values (:b, 'HACK', 'x')").param("b", B).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void impossibleDeModifierUneLigneDUnAutreTenant() {
        int updated = inTenant(A, () -> jdbc.sql("update site set name = 'pirate' where tenant_id = :b")
                .param("b", B).update());
        assertThat(updated).isZero();
    }

    @Test
    void journalDAuditEnAjoutSeulEtIsole() {
        inTenant(A, () -> jdbc.sql("insert into audit_log (tenant_id, action) values (:a, 'TEST')")
                .param("a", A).update());
        assertThat(inTenant(B, () -> jdbc.sql("select count(*) from audit_log where action = 'TEST'")
                .query(Long.class).single())).isZero();
        assertThat(inTenant(A, () -> jdbc.sql("select count(*) from audit_log where action = 'TEST'")
                .query(Long.class).single())).isPositive();
        assertThatThrownBy(() -> inTenant(A, () -> jdbc.sql("update audit_log set action = 'X'").update()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> inTenant(A, () -> jdbc.sql("delete from audit_log").update()))
                .isInstanceOf(DataAccessException.class);
    }
}
