package com.essor.qms.shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Gestionnaire de transactions qui positionne, au début de chaque transaction,
 * les variables de session PostgreSQL utilisées par les politiques RLS.
 * {@code set_config(..., true)} équivaut à {@code SET LOCAL} : la valeur disparaît
 * à la fin de la transaction, aucune fuite entre requêtes via le pool.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    private static final String SQL =
            "select set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        ExecutionContext.Context ctx = ExecutionContext.current();
        try (PreparedStatement ps = con.prepareStatement(SQL)) {
            ps.setString(1, asText(ctx.tenantId()));
            ps.setString(2, asText(ctx.userId()));
            ps.execute();
        }
    }

    private static String asText(UUID id) {
        return id == null ? "" : id.toString();
    }
}
