package com.essor.qms.records;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Événements publiés par le moteur de fiches (API du module {@code records}), consommés notamment par
 * {@code notifications}. Publiés dans la transaction métier, avec le contexte (tenant, utilisateur) courant.
 */
public final class RecordEvents {

    private RecordEvents() {
    }

    /** Fiche (ou action) assignée à un responsable. {@code actorId} peut être nul (tâche planifiée). */
    public record RecordAssigned(UUID recordId, String reference, String title, UUID ownerId, UUID actorId) {
    }

    /**
     * Transition effectuée. {@code nextRoles} : rôles autorisés sur les transitions possibles depuis le nouveau
     * statut (vide si l'état est final).
     */
    public record RecordTransitioned(UUID recordId, String reference, String title, String transitionLabel,
                                     String statusLabel, UUID ownerId, List<String> nextRoles, UUID actorId) {
    }

    public enum DeadlineKind { OVERDUE, DUE_SOON }

    /** Échéance dépassée ou à J-2, détectée par la tâche planifiée quotidienne. */
    public record RecordDeadline(UUID recordId, String reference, String title, UUID ownerId, LocalDate dueDate,
                                 DeadlineKind kind) {
    }
}
