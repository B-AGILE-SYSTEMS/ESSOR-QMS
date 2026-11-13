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

    /** {@code REVIEW_DUE} : revue périodique d'un document à J-30 ({@code dueDate} = date de revue). */
    public enum DeadlineKind { OVERDUE, DUE_SOON, REVIEW_DUE }

    /** Échéance dépassée ou à J-2, détectée par la tâche planifiée quotidienne. */
    public record RecordDeadline(UUID recordId, String reference, String title, UUID ownerId, LocalDate dueDate,
                                 DeadlineKind kind) {
    }

    /** Version d'un document diffusée : les destinataires doivent en accuser lecture. */
    public record DocumentDiffused(UUID recordId, String reference, String title, int version, List<UUID> recipients,
                                   UUID actorId) {
    }
}
