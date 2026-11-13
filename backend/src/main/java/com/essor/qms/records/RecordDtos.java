package com.essor.qms.records;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Corps et réponses de l'API des fiches (contrat M1). */
final class RecordDtos {

    private RecordDtos() {
    }

    record CreateRecord(
            @NotBlank @Size(max = 30) String type,
            @NotNull UUID siteId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            @Pattern(regexp = "^(mineure|majeure|critique)$", message = "mineure, majeure ou critique") String severity,
            Map<String, Object> data,
            UUID clientId,
            Instant occurredAt) {
    }

    record CreateAction(
            @NotBlank @Pattern(regexp = "^(curative|corrective|preventive)$",
                    message = "curative, corrective ou preventive") String actionType,
            @NotBlank @Size(max = 200) String title,
            @NotNull UUID ownerId,
            @NotNull LocalDate dueDate) {
    }

    record TransitionRequest(@Size(max = 2000) String comment) {
    }

    record AvailableTransition(String code, String label, List<String> missing) {
    }

    record RecordSummary(UUID id, String reference, String type, String status, String statusLabel, String title,
                         String severity, UUID siteId, String siteName, UUID ownerId, String ownerName,
                         LocalDate dueDate, Instant createdAt, boolean overdue, UUID clientId, Instant occurredAt,
                         Instant updatedAt, String recordType) {
    }

    record RecordView(UUID id, String reference, String type, String status, String statusLabel, String title,
                      String description, String severity, UUID siteId, String siteName, UUID ownerId,
                      String ownerName, LocalDate dueDate, String actionType, Map<String, Object> data, Instant createdAt,
                      UUID createdBy, String createdByName, UUID parentId,
                      List<AvailableTransition> availableTransitions, List<RecordSummary> children,
                      UUID clientId, Instant occurredAt, Instant updatedAt) {
    }

    /** Réponse de {@code GET /api/sync/changes}. */
    record SyncChanges(List<RecordSummary> records, Instant serverTime) {
    }

    record Page<T>(List<T> items, long total, int page, int size) {
    }

    /** {@code diff} : champ -> {old, new} (modification) ou {from, to} (transition). */
    record HistoryEntry(Instant at, String author, String action, String transition, String comment,
                        Map<String, Object> diff) {
    }

    /**
     * {@code regulatoryComplianceRate} : % (0-100, une décimale) des exigences applicables évaluées conformes
     * (partiel = 0,5), nul sans évaluation ; {@code calibrationsDueSoon} : instruments en service à étalonner sous
     * 15 jours ; {@code instrumentsOverdue} : instruments en service à échéance dépassée.
     */
    record DashboardSummary(long ncOpen, long ncToQualify, long actionsOverdue, java.math.BigDecimal ncCostMonth,
                            Double regulatoryComplianceRate, long calibrationsDueSoon, long instrumentsOverdue) {
    }
}
