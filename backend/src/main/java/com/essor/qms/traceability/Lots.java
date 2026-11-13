package com.essor.qms.traceability;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API du module {@code traceability} pour les autres modules (métrologie : effet instrument). Passe par le même
 * chemin que l'API lots : historique, audit, webhook {@code lot.status_changed}.
 */
@Service
public class Lots {

    /** Statuts qui ne sont pas remis en quarantaine (déjà bloqués ou définitifs). */
    private static final Set<String> SKIPPED = Set.of("QUARANTAINE", "REFUSE", "DETRUIT");

    private final LotService lots;
    private final JdbcClient jdbc;

    Lots(LotService lots, JdbcClient jdbc) {
        this.lots = lots;
        this.jdbc = jdbc;
    }

    /** Met le lot en QUARANTAINE ; rend false si son statut ne le permet pas (déjà bloqué, refusé, détruit). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean quarantine(UUID lotId, String reason, UUID ncId) {
        String status = jdbc.sql("select quality_status from lot where id = :id").param("id", lotId)
                .query(String.class).optional().orElse(null);
        if (status == null || SKIPPED.contains(status)) {
            return false;
        }
        lots.applyStatus(lotId, "QUARANTAINE", reason, ncId, null);
        return true;
    }
}
