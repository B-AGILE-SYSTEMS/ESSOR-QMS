package com.essor.qms.records;

import com.essor.qms.records.RecordDtos.AvailableTransition;
import com.essor.qms.records.RecordDtos.CreateAction;
import com.essor.qms.records.RecordDtos.CreateRecord;
import com.essor.qms.records.RecordDtos.HistoryEntry;
import com.essor.qms.records.RecordDtos.Page;
import com.essor.qms.records.RecordDtos.RecordSummary;
import com.essor.qms.records.RecordDtos.RecordView;
import com.essor.qms.records.RecordEvents.RecordAssigned;
import com.essor.qms.records.RecordEvents.RecordTransitioned;
import com.essor.qms.records.RecordStore.Row;
import com.essor.qms.records.RecordTypes.RecordType;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.ExecutionContext;
import com.essor.qms.records.RecordDtos.SyncChanges;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Moteur générique de fiches : création, lecture, modification, transitions de workflow, actions, historique. */
@Service
@Transactional
class RecordService {

    static final String ACTION_TYPE = "ACTION";
    static final String DOCUMENT_TYPE = "DOCUMENT";

    /** Changement de statut d'une fiche (interne au module : réactions par type de fiche, ex. NC d'audit). */
    record StatusChanged(UUID recordId, String typeCode, String from, String to) {
    }
    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final int MAX_SYNC_RECORDS = 500;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_DATA_BYTES = 64 * 1024;
    private static final Pattern SEVERITY = Pattern.compile("^(mineure|majeure|critique)$");
    private static final Set<String> PATCHABLE = Set.of(
            "title", "description", "severity", "siteId", "ownerId", "dueDate", "data");

    private final RecordStore store;
    private final RecordTypes types;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;

    RecordService(RecordStore store, RecordTypes types, AuditLog audit, ApplicationEventPublisher events) {
        this.store = store;
        this.types = types;
        this.audit = audit;
        this.events = events;
    }

    // ---------------------------------------------------------------- lecture

    @Transactional
    Page<RecordSummary> list(String type, String status, String severity, UUID siteId, String q, int page, int size) {
        StringBuilder where = new StringBuilder("true");
        Map<String, Object> p = new HashMap<>();
        if (type != null && !type.isBlank()) {
            where.append(" and r.type_code = :type");
            p.put("type", type);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = :status");
            p.put("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            where.append(" and r.severity = :severity");
            p.put("severity", severity);
        }
        if (siteId != null) {
            where.append(" and r.site_id = :site");
            p.put("site", siteId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (r.title ilike :q escape '\\' or r.reference ilike :q escape '\\')");
            p.put("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Map<String, RecordType> all = types.all();
        List<RecordSummary> items = store.query(where.toString(), p, "r.created_at desc, r.reference desc",
                size, (long) page * size).stream().map(r -> summary(r, all)).toList();
        return new Page<>(items, store.count(where.toString(), p), page, size);
    }

    @Transactional
    RecordView get(UUID id) {
        return view(load(id, false));
    }

    @Transactional
    List<HistoryEntry> history(UUID id) {
        load(id, false);
        record H(long id, OffsetDateTime occurredAt, String action, UUID actorId, String actorName, String details) {
        }
        return store.jdbc().sql("""
                select a.id, a.occurred_at, a.action, a.actor_id, coalesce(u.display_name, u.username) as actor_name,
                       a.details::text as details
                from audit_log a left join app_user u on u.id = a.actor_id
                where a.entity_type = 'record' and a.entity_id = :id
                order by a.occurred_at, a.id
                """).param("id", id.toString()).query(H.class).list().stream()
                .map(h -> historyEntry(h.occurredAt(), h.actorName(), h.action(), store.parse(h.details())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static HistoryEntry historyEntry(OffsetDateTime at, String author, String action,
                                             Map<String, Object> details) {
        Map<String, Object> diff = new LinkedHashMap<>();
        if (details.get("changes") instanceof Map<?, ?> changes) {
            diff.putAll((Map<String, Object>) changes);
        }
        if (details.containsKey("from")) {
            diff.put("status", Map.of("from", details.get("from"), "to", details.get("to")));
        }
        Object transition = details.get("transition");
        Object comment = details.get("comment");
        return new HistoryEntry(at.toInstant(), author, action, transition == null ? null : transition.toString(),
                comment == null ? null : comment.toString(), diff);
    }

    /** Fiches non terminées dont l'utilisateur est responsable ou sur lesquelles il peut agir. */
    @Transactional
    List<RecordSummary> myTasks(int limit) {
        Map<String, RecordType> all = types.all();
        Set<String> roles = store.currentRoles();
        List<String> actionable = new ArrayList<>();
        List<String> finals = new ArrayList<>();
        for (RecordType t : all.values()) {
            for (Workflow.State s : t.workflow().states()) {
                if (s.isFinal()) {
                    finals.add(t.code() + ":" + s.code());
                } else if (t.workflow().from(s.code()).stream().anyMatch(tr -> hasRole(roles, tr))) {
                    actionable.add(t.code() + ":" + s.code());
                }
            }
        }
        Map<String, Object> p = Map.of("act", actionable.toArray(String[]::new),
                "fin", finals.toArray(String[]::new));
        String where = """
                (r.type_code || ':' || r.status) <> all(cast(:fin as text[]))
                and (r.owner_id = app_current_user() or (r.type_code || ':' || r.status) = any(cast(:act as text[])))
                """;
        return store.query(where, p, "r.due_date nulls last, r.created_at", limit, 0).stream()
                .map(r -> summary(r, all)).toList();
    }

    /**
     * Fiches dont l'utilisateur est responsable ou déclarant, modifiées strictement après {@code since}
     * (toutes si absent). Au plus {@value #MAX_SYNC_RECORDS} ; si la limite est atteinte, {@code serverTime}
     * vaut la dernière date de modification rendue, pour que l'appel suivant reprenne là.
     */
    @Transactional
    SyncChanges changes(Instant since) {
        OffsetDateTime serverTime = store.jdbc().sql("select now()").query(OffsetDateTime.class).single();
        Map<String, Object> p = new HashMap<>();
        StringBuilder where = new StringBuilder(
                "(r.owner_id = app_current_user() or r.created_by = app_current_user()) and r.updated_at <= :now");
        p.put("now", serverTime);
        if (since != null) {
            where.append(" and r.updated_at > :since");
            p.put("since", since.atOffset(ZoneOffset.UTC));
        }
        Map<String, RecordType> all = types.all();
        List<Row> rows = store.query(where.toString(), p, "r.updated_at, r.id", MAX_SYNC_RECORDS, 0);
        Instant next = rows.size() == MAX_SYNC_RECORDS
                ? rows.get(rows.size() - 1).updatedAt().toInstant() : serverTime.toInstant();
        return new SyncChanges(rows.stream().map(r -> summary(r, all)).toList(), next);
    }

    // ---------------------------------------------------------------- écriture

    RecordView create(CreateRecord body) {
        requireWriter();
        RecordType type = types.get(body.type());
        if (ACTION_TYPE.equals(type.code())) {
            throw ApiException.badRequest("Une action se crée depuis sa fiche parente (POST /api/records/{id}/actions)");
        }
        if (DOCUMENT_TYPE.equals(type.code())) {
            throw ApiException.badRequest("Un document se crée par POST /api/documents");
        }
        if (body.clientId() != null) {
            // renvoi depuis le téléphone (clé d'idempotence perdue) : pas de doublon, on rend la fiche existante
            var existing = store.findByClientId(body.clientId());
            if (existing.isPresent()) {
                return get(existing.get());
            }
        }
        if (!store.currentUserHasSite(body.siteId())) {
            throw ApiException.badRequest("Site inconnu ou non rattaché à l'utilisateur");
        }
        if (body.occurredAt() != null && body.occurredAt().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw ApiException.badRequest("occurredAt ne peut pas être dans le futur");
        }
        Map<String, Object> data = body.data() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body.data());
        UUID id = insert(type, body.title(), body.description(), body.severity(), body.siteId(), null, null, null,
                data);
        if (body.clientId() != null || body.occurredAt() != null) {
            store.jdbc().sql("update record set client_id = :c, occurred_at = :o where id = :id")
                    .param("c", body.clientId())
                    .param("o", body.occurredAt() == null ? null : body.occurredAt().atOffset(ZoneOffset.UTC))
                    .param("id", id).update();
        }
        audit.record("RECORD_CREATED", "record", id.toString(),
                body.clientId() == null ? null : store.write(Map.of("clientId", body.clientId())));
        Workflow wf = type.workflow();
        List<String> firstRoles = wf.from(wf.initial()).stream().flatMap(tr -> tr.roles().stream()).distinct().toList();
        RecordView created = get(id);
        events.publishEvent(new RecordTransitioned(id, created.reference(), created.title(), "Déclaration",
                wf.label(wf.initial()), null, firstRoles, ExecutionContext.userId()));
        return created;
    }

    RecordView addAction(UUID parentId, CreateAction body) {
        requireWriter();
        Row parent = load(parentId, true);
        RecordType parentType = types.get(parent.typeCode());
        if (parentType.workflow().isFinal(parent.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked", "Fiche terminée : ajout d'action impossible");
        }
        if (!store.userExists(body.ownerId())) {
            throw ApiException.badRequest("Responsable inconnu dans ce tenant");
        }
        RecordType type = types.get(ACTION_TYPE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionType", body.actionType());
        UUID id = insert(type, body.title(), null, null, parent.siteId(), body.ownerId(), body.dueDate(), parentId,
                data);
        audit.record("RECORD_CREATED", "record", id.toString(), store.write(Map.of("parentId", parentId)));
        audit.record("ACTION_ADDED", "record", parentId.toString(), store.write(Map.of("actionId", id)));
        Row created = load(id, false);
        events.publishEvent(new RecordAssigned(id, created.reference(), created.title(), body.ownerId(),
                ExecutionContext.userId()));
        return view(created);
    }

    RecordView patch(UUID id, Map<String, Object> body) {
        requireWriter();
        if (body == null || body.isEmpty()) {
            throw ApiException.badRequest("Aucun champ à modifier");
        }
        Row row = load(id, true);
        RecordType type = types.get(row.typeCode());
        if (type.workflow().isFinal(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked",
                    "Fiche au statut « " + type.workflow().label(row.status()) + " » : modification impossible");
        }
        for (String key : body.keySet()) {
            if (!PATCHABLE.contains(key)) {
                throw ApiException.badRequest("Champ non modifiable : " + key);
            }
        }
        Patch patch = Patch.of(row, store.data(row), body);
        validatePatch(row, patch);
        Map<String, Object> changes = patch.diff(row, store.data(row));
        if (changes.isEmpty()) {
            return view(row);
        }
        store.jdbc().sql("""
                update record set title = :title, description = :description, severity = :severity,
                       site_id = :site, owner_id = :owner, due_date = :due, data = cast(:data as jsonb),
                       updated_at = now()
                where id = :id
                """)
                .param("title", patch.title()).param("description", patch.description())
                .param("severity", patch.severity()).param("site", patch.siteId()).param("owner", patch.ownerId())
                .param("due", patch.dueDate()).param("data", checkedData(patch.data())).param("id", id)
                .update();
        audit.record("RECORD_UPDATED", "record", id.toString(), store.write(Map.of("changes", changes)));
        if (patch.ownerId() != null && !patch.ownerId().equals(row.ownerId())) {
            events.publishEvent(new RecordAssigned(id, row.reference(), patch.title(), patch.ownerId(),
                    ExecutionContext.userId()));
        }
        return get(id);
    }

    /**
     * Fiche créée par un autre service du module (NC d'audit, document), sans contrôle du site de l'utilisateur.
     * Journalisée et notifiée comme une déclaration.
     */
    UUID createInternal(String typeCode, String title, String description, String severity, UUID siteId,
                        UUID ownerId, Map<String, Object> data, Map<String, Object> auditDetails) {
        RecordType type = types.get(typeCode);
        String cut = title.length() > 200 ? title.substring(0, 197) + "..." : title;
        UUID id = insert(type, cut, description, severity, siteId, ownerId, null, null,
                data == null ? new LinkedHashMap<>() : data);
        audit.record("RECORD_CREATED", "record", id.toString(),
                auditDetails == null ? null : store.write(auditDetails));
        Workflow wf = type.workflow();
        List<String> firstRoles = wf.from(wf.initial()).stream().flatMap(tr -> tr.roles().stream()).distinct().toList();
        Row created = load(id, false);
        events.publishEvent(new RecordTransitioned(id, created.reference(), created.title(), "Déclaration",
                wf.label(wf.initial()), ownerId, firstRoles, ExecutionContext.userId()));
        return id;
    }

    RecordView transition(UUID id, String code, String comment) {
        Row row = load(id, true);
        if (DOCUMENT_TYPE.equals(row.typeCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "transition_impossible",
                    "Le workflow d'un document s'applique à ses versions "
                            + "(POST /api/documents/{id}/versions/{v}/transitions/{code})", Map.of("status", row.status()));
        }
        RecordType type = types.get(row.typeCode());
        Workflow wf = type.workflow();
        Workflow.Transition t = wf.from(row.status()).stream().filter(tr -> tr.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "transition_impossible",
                        "Transition « " + code + " » impossible depuis le statut « " + wf.label(row.status()) + " »",
                        Map.of("status", row.status())));
        if (!hasRole(rolesFor(row), t)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Transition « " + t.label() + " » réservée aux rôles : " + String.join(", ", t.roles()),
                    Map.of("roles", t.roles()));
        }
        List<String> missing = missing(row, type, t);
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "requirements_missing",
                    "Transition « " + t.label() + " » impossible, il manque : " + String.join(", ", missing),
                    Map.of("missing", missing));
        }
        store.jdbc().sql("update record set status = :s, updated_at = now() where id = :id")
                .param("s", t.to()).param("id", id).update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transition", t.code());
        details.put("from", row.status());
        details.put("to", t.to());
        if (comment != null && !comment.isBlank()) {
            details.put("comment", comment.strip());
        }
        audit.record("TRANSITION", "record", id.toString(), store.write(details));
        events.publishEvent(new StatusChanged(id, row.typeCode(), row.status(), t.to()));
        List<String> nextRoles = wf.from(t.to()).stream().flatMap(tr -> tr.roles().stream()).distinct().toList();
        events.publishEvent(new RecordTransitioned(id, row.reference(), row.title(), t.label(), wf.label(t.to()),
                row.ownerId(), nextRoles, ExecutionContext.userId()));
        return get(id);
    }

    // ---------------------------------------------------------------- interne

    private UUID insert(RecordType type, String title, String description, String severity, UUID siteId,
                        UUID ownerId, LocalDate dueDate, UUID parentId, Map<String, Object> data) {
        UUID id = UUID.randomUUID();
        String reference = store.nextReference(type.refPrefix(), LocalDate.now(ZONE).getYear());
        store.jdbc().sql("""
                insert into record (id, tenant_id, type_code, reference, status, title, description, severity,
                                    site_id, owner_id, due_date, parent_id, data, created_by)
                values (:id, app_current_tenant(), :type, :ref, :status, :title, :description, :severity,
                        :site, :owner, :due, :parent, cast(:data as jsonb), app_current_user())
                """)
                .param("id", id).param("type", type.code()).param("ref", reference)
                .param("status", type.workflow().initial()).param("title", title.strip())
                .param("description", description).param("severity", severity).param("site", siteId)
                .param("owner", ownerId).param("due", dueDate).param("parent", parentId)
                .param("data", checkedData(data)).update();
        return id;
    }

    private String checkedData(Map<String, Object> data) {
        String text = store.write(data == null ? Map.of() : data);
        if (text.length() > MAX_DATA_BYTES) {
            throw ApiException.badRequest("Champs personnalisés trop volumineux (64 Ko maximum)");
        }
        return text;
    }

    private void validatePatch(Row row, Patch p) {
        if (p.title() == null || p.title().isBlank() || p.title().length() > 200) {
            throw ApiException.badRequest("Titre obligatoire (200 caractères max)");
        }
        if (p.description() != null && p.description().length() > 10000) {
            throw ApiException.badRequest("Description trop longue (10 000 caractères max)");
        }
        if (p.severity() != null && !SEVERITY.matcher(p.severity()).matches()) {
            throw ApiException.badRequest("Gravité : mineure, majeure ou critique");
        }
        if (p.ownerId() != null && !p.ownerId().equals(row.ownerId()) && !store.userExists(p.ownerId())) {
            throw ApiException.badRequest("Responsable inconnu dans ce tenant");
        }
        if (p.siteId() != null && !p.siteId().equals(row.siteId()) && !store.currentUserHasSite(p.siteId())) {
            throw ApiException.badRequest("Site inconnu ou non rattaché à l'utilisateur");
        }
    }

    Row load(UUID id, boolean forUpdate) {
        return store.find(id, forUpdate).orElseThrow(() -> ApiException.notFound("Fiche"));
    }

    void requireWriter() {
        Set<String> roles = store.currentRoles();
        if (roles.isEmpty() || roles.equals(Set.of("READER"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Accès en lecture seule");
        }
    }

    /** Rôles effectifs sur une fiche : un auditeur désigné d'un audit ({@code data.auditeurs}) agit comme AUDITOR. */
    Set<String> rolesFor(Row r) {
        Set<String> roles = store.currentRoles();
        if (AuditService.AUDIT_TYPE.equals(r.typeCode()) && !roles.contains("AUDITOR")
                && AuditService.isDesignatedAuditor(store.data(r))) {
            Set<String> withAuditor = new java.util.HashSet<>(roles);
            withAuditor.add("AUDITOR");
            return Set.copyOf(withAuditor);
        }
        return roles;
    }

    static boolean hasRole(Set<String> roles, Workflow.Transition t) {
        return t.roles() != null && t.roles().stream().anyMatch(roles::contains);
    }

    /** Ce qui manque pour effectuer la transition : champs requis vides, fiches enfants non terminées. */
    private List<String> missing(Row row, RecordType type, Workflow.Transition t) {
        Map<String, Object> data = store.data(row);
        List<String> missing = new ArrayList<>();
        for (String field : t.requiresOrEmpty()) {
            if (isBlank(fieldValue(row, data, field))) {
                missing.add(type.fieldLabel(field));
            }
        }
        if (!t.childrenInOrEmpty().isEmpty()) {
            List<Row> children = store.children(row.id());
            long notDone = children.stream().filter(c -> !t.childrenInOrEmpty().contains(c.status())).count();
            if (children.isEmpty()) {
                missing.add("au moins une action");
            } else if (notDone > 0) {
                missing.add("actions non terminées (" + notDone + ")");
            }
        }
        return missing;
    }

    private static Object fieldValue(Row row, Map<String, Object> data, String field) {
        return switch (field) {
            case "title" -> row.title();
            case "description" -> row.description();
            case "severity" -> row.severity();
            case "siteId" -> row.siteId();
            case "ownerId" -> row.ownerId();
            case "dueDate" -> row.dueDate();
            default -> data.get(field);
        };
    }

    private static boolean isBlank(Object v) {
        return v == null
                || (v instanceof String s && s.isBlank())
                || (v instanceof Collection<?> c && c.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty());
    }

    private RecordView view(Row r) {
        Map<String, RecordType> all = types.all();
        RecordType type = all.get(r.typeCode());
        Set<String> roles = rolesFor(r);
        List<AvailableTransition> available = type.workflow().from(r.status()).stream()
                .filter(t -> hasRole(roles, t))
                .map(t -> new AvailableTransition(t.code(), t.label(), missing(r, type, t)))
                .toList();
        List<RecordSummary> children = store.children(r.id()).stream().map(c -> summary(c, all)).toList();
        return new RecordView(r.id(), r.reference(), r.typeCode(), r.status(), type.workflow().label(r.status()),
                r.title(), r.description(), r.severity(), r.siteId(), r.siteName(), r.ownerId(), r.ownerName(),
                r.dueDate(), actionType(r), store.data(r), r.createdAt().toInstant(), r.createdBy(), r.createdByName(),
                r.parentId(), available, children, r.clientId(), instant(r.occurredAt()), instant(r.updatedAt()));
    }

    private String actionType(Row r) {
        Object t = ACTION_TYPE.equals(r.typeCode()) ? store.data(r).get("actionType") : null;
        return t == null ? null : t.toString();
    }

    static RecordSummary summary(Row r, Map<String, RecordType> all) {
        RecordType type = all.get(r.typeCode());
        Workflow wf = type == null ? null : type.workflow();
        boolean done = wf == null || wf.isFinal(r.status()) || doneStates(all).contains(r.status());
        boolean overdue = !done && r.dueDate() != null && r.dueDate().isBefore(LocalDate.now(ZONE));
        return new RecordSummary(r.id(), r.reference(), r.typeCode(), r.status(),
                wf == null ? r.status() : wf.label(r.status()), r.title(), r.severity(), r.siteId(), r.siteName(),
                r.ownerId(), r.ownerName(), r.dueDate(), r.createdAt().toInstant(), overdue, r.clientId(),
                instant(r.occurredAt()), instant(r.updatedAt()), r.typeCode());
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    /** Statuts « réalisés » : ceux qu'une transition parente attend des enfants (ex. FAITE). */
    static Set<String> doneStates(Map<String, RecordType> all) {
        Set<String> done = new LinkedHashSet<>();
        all.values().forEach(t -> t.workflow().transitions().forEach(tr -> done.addAll(tr.childrenInOrEmpty())));
        return done;
    }

    /** Valeurs après application d'un PATCH (clé présente = remplacée, {@code null} = effacée). */
    record Patch(String title, String description, String severity, UUID siteId, UUID ownerId, LocalDate dueDate,
                 Map<String, Object> data) {

        @SuppressWarnings("unchecked")
        static Patch of(Row row, Map<String, Object> currentData, Map<String, Object> body) {
            Map<String, Object> data = new LinkedHashMap<>(currentData);
            if (body.containsKey("data")) {
                Object d = body.get("data");
                if (d != null && !(d instanceof Map)) {
                    throw ApiException.badRequest("data doit être un objet");
                }
                if (d != null) {
                    ((Map<String, Object>) d).forEach((k, v) -> {
                        if (v == null) {
                            data.remove(k);
                        } else {
                            data.put(k, v);
                        }
                    });
                }
            }
            return new Patch(
                    body.containsKey("title") ? str(body, "title") : row.title(),
                    body.containsKey("description") ? str(body, "description") : row.description(),
                    body.containsKey("severity") ? str(body, "severity") : row.severity(),
                    body.containsKey("siteId") ? uuid(body, "siteId") : row.siteId(),
                    body.containsKey("ownerId") ? uuid(body, "ownerId") : row.ownerId(),
                    body.containsKey("dueDate") ? date(body, "dueDate") : row.dueDate(),
                    data);
        }

        Map<String, Object> diff(Row row, Map<String, Object> oldData) {
            Map<String, Object> changes = new LinkedHashMap<>();
            put(changes, "title", row.title(), title);
            put(changes, "description", row.description(), description);
            put(changes, "severity", row.severity(), severity);
            put(changes, "siteId", row.siteId(), siteId);
            put(changes, "ownerId", row.ownerId(), ownerId);
            put(changes, "dueDate", row.dueDate(), dueDate);
            Set<String> keys = new LinkedHashSet<>(oldData.keySet());
            keys.addAll(data.keySet());
            for (String k : keys) {
                put(changes, "data." + k, oldData.get(k), data.get(k));
            }
            return changes;
        }

        private static void put(Map<String, Object> changes, String field, Object before, Object after) {
            if (!Objects.equals(before, after)) {
                Map<String, Object> c = new HashMap<>();
                c.put("old", before == null ? null : before.toString());
                c.put("new", after == null ? null : after.toString());
                changes.put(field, c);
            }
        }

        private static String str(Map<String, Object> body, String key) {
            Object v = body.get(key);
            if (v != null && !(v instanceof String)) {
                throw ApiException.badRequest(key + " doit être un texte");
            }
            return v == null ? null : ((String) v).strip();
        }

        private static UUID uuid(Map<String, Object> body, String key) {
            String v = str(body, key);
            try {
                return v == null || v.isEmpty() ? null : UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(key + " : identifiant invalide");
            }
        }

        private static LocalDate date(Map<String, Object> body, String key) {
            String v = str(body, key);
            try {
                return v == null || v.isEmpty() ? null : LocalDate.parse(v);
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest(key + " : date attendue au format AAAA-MM-JJ");
            }
        }
    }
}
