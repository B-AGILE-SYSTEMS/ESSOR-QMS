package com.essor.qms.shared;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Contexte d'exécution de la requête courante : utilisateur et tenant.
 * Lu par {@link TenantAwareTransactionManager} au début de chaque transaction
 * pour positionner {@code app.tenant_id} et {@code app.user_id} (SET LOCAL).
 */
public final class ExecutionContext {

    public record Context(UUID userId, UUID tenantId) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ExecutionContext() {
    }

    public static Context current() {
        Context c = CURRENT.get();
        return c == null ? new Context(null, null) : c;
    }

    public static UUID userId() {
        return current().userId();
    }

    public static UUID tenantId() {
        return current().tenantId();
    }

    public static void set(UUID userId, UUID tenantId) {
        CURRENT.set(new Context(userId, tenantId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Exécute {@code action} avec un contexte donné, puis restaure le précédent. */
    public static <T> T callAs(UUID userId, UUID tenantId, Supplier<T> action) {
        Context previous = CURRENT.get();
        set(userId, tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
