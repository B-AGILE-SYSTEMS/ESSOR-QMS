package com.essor.qms.records;

import com.essor.qms.records.RecordEvents.DocumentDiffused;
import com.essor.qms.records.RecordStore.Row;
import com.essor.qms.records.RecordTypes.RecordType;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.essor.qms.shared.ExecutionContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion documentaire (type de fiche DOCUMENT) : versions (PDF/DOCX/XLSX ≤ 20 Mo), workflow par version,
 * une seule version en vigueur, destinataires et accusés de lecture.
 * Fichiers sur disque local ({@code essor.storage.dir}/documents/&lt;tenant&gt;/&lt;versionId&gt;).
 */
@Service
@Transactional
class DocumentService {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final String DIFFUSED = "DIFFUSE";
    static final String OBSOLETE = "PERIME";
    static final Set<String> DOC_TYPES = Set.of("procedure", "instruction", "formulaire", "enregistrement");
    static final String PDF_TYPE = "application/pdf";
    static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,39}$");
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4};

    record Recipients(List<String> roles, List<UUID> siteIds) {
    }

    record CreateDocument(String code, String title, String docType, String processus, UUID siteId, UUID ownerId,
                          Recipients recipients, List<String> verifierRoles, List<String> approverRoles,
                          LocalDate reviewDate, List<UUID> requirementIds) {
    }

    record DocumentSummary(UUID id, String reference, String code, String title, String docType, String processus,
                           String status, String statusLabel, CurrentVersion currentVersion, Integer latestVersion,
                           String reviewDate, Instant updatedAt) {
    }

    /** Version en vigueur (null tant qu'aucune n'est diffusée). */
    record CurrentVersion(UUID id, int number, String fileName) {
    }

    record VersionView(UUID id, int number, String status, String statusLabel, String note, String fileName,
                       String contentType, long size, Instant createdAt, String createdByName, Instant diffusedAt,
                       boolean inForce, List<RecordDtos.AvailableTransition> availableTransitions) {
    }

    record DocumentView(DocumentSummary document, UUID ownerId, String ownerName, Map<String, Object> recipients,
                        List<String> verifierRoles, List<String> approverRoles,
                        List<StandardService.RequirementRef> requirements, List<VersionView> versions) {
    }

    record Reader(UUID userId, String name, Instant ackedAt) {
    }

    record Acks(int version, List<Reader> acknowledged, List<Reader> pending) {
    }

    record Download(String fileName, String contentType, byte[] content) {
    }

    private record VRow(UUID id, int number, String status, String note, String fileName, String contentType,
                        long size, OffsetDateTime createdAt, String createdByName, OffsetDateTime diffusedAt,
                        String storagePath) {
    }

    private static final String V_SELECT = """
            select v.id, v.number, v.status, v.note, v.file_name, v.content_type, v.size, v.created_at,
                   coalesce(u.display_name, u.username) as created_by_name, v.diffused_at, v.storage_path
            from document_version v left join app_user u on u.id = v.created_by
            """;

    private final RecordStore store;
    private final RecordService records;
    private final RecordTypes types;
    private final StandardService standards;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final Path root;

    DocumentService(RecordStore store, RecordService records, RecordTypes types, StandardService standards,
                    AuditLog audit, ApplicationEventPublisher events, @Value("${essor.storage.dir}") Path storage) {
        this.store = store;
        this.records = records;
        this.types = types;
        this.standards = standards;
        this.audit = audit;
        this.events = events;
        this.root = storage.resolve("documents").normalize();
    }

    // ---------------------------------------------------------------- documents

    DocumentView create(CreateDocument b) {
        records.requireWriter();
        if (b == null || b.code() == null || !CODE.matcher(b.code().strip()).matches()) {
            throw ApiException.badRequest("code obligatoire (lettres, chiffres, . _ -, 40 caractères max)");
        }
        if (b.title() == null || b.title().isBlank() || b.title().length() > 200) {
            throw ApiException.badRequest("title obligatoire (200 caractères max)");
        }
        if (b.docType() == null || !DOC_TYPES.contains(b.docType())) {
            throw ApiException.badRequest("docType : procedure, instruction, formulaire ou enregistrement");
        }
        if (b.processus() != null && b.processus().length() > 200) {
            throw ApiException.badRequest("processus trop long (200 caractères max)");
        }
        String code = b.code().strip();
        if (store.jdbc().sql("select count(*) from record where type_code = 'DOCUMENT' and data->>'code' = :c")
                .param("c", code).query(Long.class).single() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid", "Code de document déjà utilisé : " + code);
        }
        if (b.siteId() != null && !store.currentUserHasSite(b.siteId())) {
            throw ApiException.badRequest("Site inconnu ou non rattaché à l'utilisateur");
        }
        if (b.ownerId() != null && !store.userExists(b.ownerId())) {
            throw ApiException.badRequest("Responsable inconnu dans ce tenant");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("docType", b.docType());
        if (b.processus() != null && !b.processus().isBlank()) {
            data.put("processus", b.processus().strip());
        }
        data.put("recipients", recipients(b.recipients()));
        data.put("verifierRoles", roles(b.verifierRoles(), "verifierRoles"));
        data.put("approverRoles", roles(b.approverRoles(), "approverRoles"));
        if (b.reviewDate() != null) {
            data.put("reviewDate", b.reviewDate().toString());
        }
        UUID owner = b.ownerId() != null ? b.ownerId() : ExecutionContext.userId();
        UUID id = records.createInternal(RecordService.DOCUMENT_TYPE, b.title().strip(), null, null, b.siteId(),
                owner, data, Map.of("code", code));
        if (b.requirementIds() != null && !b.requirementIds().isEmpty()) {
            standards.replaceLinks(id, b.requirementIds());
        }
        return get(id);
    }

    private Map<String, Object> recipients(Recipients r) {
        List<String> roles = roles(r == null ? null : r.roles(), "recipients.roles");
        List<String> sites = new ArrayList<>();
        if (r != null && r.siteIds() != null) {
            for (UUID s : r.siteIds()) {
                if (s == null || store.jdbc().sql("select count(*) from site where id = :s").param("s", s)
                        .query(Long.class).single() == 0) {
                    throw ApiException.badRequest("recipients.siteIds : site inconnu dans ce tenant");
                }
                sites.add(s.toString());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roles", roles);
        m.put("siteIds", sites);
        return m;
    }

    private static List<String> roles(List<String> roles, String name) {
        if (roles == null) {
            return List.of();
        }
        if (!Workflow.ROLES.containsAll(roles)) {
            throw ApiException.badRequest(name + " : rôles parmi " + Workflow.ROLES);
        }
        return List.copyOf(new java.util.LinkedHashSet<>(roles));
    }

    List<DocumentSummary> list(String status, String type, String q, Boolean inForce) {
        StringBuilder where = new StringBuilder("r.type_code = 'DOCUMENT'");
        Map<String, Object> p = new HashMap<>();
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = :status");
            p.put("status", status);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and r.data->>'docType' = :type");
            p.put("type", type);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (r.title ilike :q escape '\\' or r.data->>'code' ilike :q escape '\\'"
                    + " or r.reference ilike :q escape '\\')");
            p.put("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (Boolean.TRUE.equals(inForce)) {
            where.append(" and exists (select 1 from document_version v where v.document_id = r.id"
                    + " and v.status = 'DIFFUSE')");
        }
        Workflow wf = types.get(RecordService.DOCUMENT_TYPE).workflow();
        return store.query(where.toString(), p, "r.data->>'code', r.reference", 500, 0).stream()
                .map(r -> summary(r, wf)).toList();
    }

    DocumentView get(UUID id) {
        Row r = loadDocument(id, false);
        RecordType type = types.get(RecordService.DOCUMENT_TYPE);
        Map<String, Object> data = store.data(r);
        Set<String> myRoles = store.currentRoles();
        List<VersionView> versions = versionRows(id).stream().map(v -> version(v, type.workflow(), data, myRoles))
                .toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> recipients = data.get("recipients") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of("roles", List.of(), "siteIds", List.of());
        return new DocumentView(summary(r, type.workflow()), r.ownerId(), r.ownerName(), recipients,
                strings(data.get("verifierRoles")), strings(data.get("approverRoles")), standards.links(id),
                versions);
    }

    private DocumentSummary summary(Row r, Workflow wf) {
        Map<String, Object> d = store.data(r);
        int max = store.jdbc().sql("select coalesce(max(number), 0) from document_version where document_id = :d")
                .param("d", r.id()).query(Integer.class).single();
        Integer latest = max == 0 ? null : max;
        CurrentVersion current = store.jdbc().sql("""
                select id, number, file_name from document_version where document_id = :d and status = 'DIFFUSE'
                """).param("d", r.id()).query(CurrentVersion.class).optional().orElse(null);
        return new DocumentSummary(r.id(), r.reference(), str(d.get("code")), r.title(), str(d.get("docType")),
                str(d.get("processus")), r.status(), wf.label(r.status()), current, latest,
                str(d.get("reviewDate")), r.updatedAt() == null ? null : r.updatedAt().toInstant());
    }

    // ---------------------------------------------------------------- versions

    VersionView addVersion(UUID documentId, String originalName, String declaredType, byte[] content, String note) {
        records.requireWriter();
        Row doc = loadDocument(documentId, true);
        if (types.get(RecordService.DOCUMENT_TYPE).workflow().isFinal(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked", "Document archivé : nouvelle version impossible");
        }
        if (note != null && note.length() > 2000) {
            throw ApiException.badRequest("Note de version trop longue (2000 caractères max)");
        }
        boolean pending = versionRows(documentId).stream()
                .anyMatch(v -> !DIFFUSED.equals(v.status()) && !OBSOLETE.equals(v.status()));
        if (pending) {
            throw new ApiException(HttpStatus.CONFLICT, "version_in_progress",
                    "Une version est déjà en cours de rédaction ou de validation");
        }
        String type = detectType(content, originalName);
        int number = store.jdbc().sql("select coalesce(max(number), 0) + 1 from document_version where document_id = :d")
                .param("d", documentId).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        UUID tenant = store.jdbc().sql("select app_current_tenant()").query(UUID.class).single();
        String relative = tenant + "/" + id;
        write(root.resolve(relative), content);
        String initial = types.get(RecordService.DOCUMENT_TYPE).workflow().initial();
        store.jdbc().sql("""
                insert into document_version (id, tenant_id, document_id, number, status, note, file_name,
                                              content_type, size, storage_path, created_by)
                values (:id, app_current_tenant(), :d, :n, :s, :note, :name, :type, :size, :path, app_current_user())
                """).param("id", id).param("d", documentId).param("n", number).param("s", initial)
                .param("note", note == null || note.isBlank() ? null : note.strip())
                .param("name", AttachmentService.safeName(originalName, PDF_TYPE)).param("type", type)
                .param("size", (long) content.length).param("path", relative).update();
        setDocumentStatus(documentId, initial);
        audit.record("DOCUMENT_VERSION_ADDED", "record", documentId.toString(),
                store.write(Map.of("version", number, "contentType", type, "size", content.length)));
        return version(documentId, number);
    }

    /** Transition du workflow sur une version ; à l'approbation, la version précédente en vigueur devient périmée. */
    VersionView transition(UUID documentId, int number, String code, String comment) {
        Row doc = loadDocument(documentId, true);
        VRow v = versionRow(documentId, number);
        Workflow wf = types.get(RecordService.DOCUMENT_TYPE).workflow();
        Map<String, Object> data = store.data(doc);
        Workflow.Transition t = wf.from(v.status()).stream().filter(tr -> tr.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "transition_impossible",
                        "Transition « " + code + " » impossible depuis le statut « " + versionLabel(wf, v.status())
                                + " »", Map.of("status", v.status())));
        List<String> allowed = allowedRoles(t, data);
        if (store.currentRoles().stream().noneMatch(allowed::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Transition « " + t.label() + " » réservée aux rôles : " + String.join(", ", allowed),
                    Map.of("roles", allowed));
        }
        boolean diffusing = DIFFUSED.equals(t.to());
        if (diffusing) {
            store.jdbc().sql("update document_version set status = 'PERIME' where document_id = :d and status = 'DIFFUSE'")
                    .param("d", documentId).update();
        }
        if (!wf.state(t.to()).map(Workflow.State::isFinal).orElse(false)) {
            store.jdbc().sql("""
                    update document_version set status = :s,
                           diffused_at = case when :s = 'DIFFUSE' then now() else diffused_at end
                    where id = :id
                    """).param("s", t.to()).param("id", v.id()).update();
        } else {
            // archivage du document : la version en vigueur devient périmée
            store.jdbc().sql("update document_version set status = 'PERIME' where id = :id").param("id", v.id())
                    .update();
        }
        setDocumentStatus(documentId, t.to());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transition", t.code());
        details.put("version", number);
        details.put("from", v.status());
        details.put("to", t.to());
        if (comment != null && !comment.isBlank()) {
            details.put("comment", comment.strip());
        }
        audit.record("TRANSITION", "record", documentId.toString(), store.write(details));
        if (diffusing) {
            events.publishEvent(new DocumentDiffused(documentId, doc.reference(), doc.title(), number,
                    recipientIds(data), ExecutionContext.userId()));
        }
        return version(documentId, number);
    }

    Download download(UUID documentId, int number) {
        loadDocument(documentId, false);
        VRow v = versionRow(documentId, number);
        Path file = root.resolve(v.storagePath()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Fichier de la version");
        }
        try {
            return new Download(v.fileName(), v.contentType(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- accusés de lecture

    Acks ack(UUID documentId, int number) {
        Row doc = loadDocument(documentId, false);
        VRow v = versionRow(documentId, number);
        if (!DIFFUSED.equals(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "transition_impossible",
                    "Seule la version en vigueur peut faire l'objet d'un accusé de lecture");
        }
        if (!recipientIds(store.data(doc)).contains(ExecutionContext.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Vous n'êtes pas destinataire de ce document");
        }
        int inserted = store.jdbc().sql("""
                insert into document_ack (tenant_id, version_id, user_id) values (app_current_tenant(), :v, app_current_user())
                on conflict do nothing
                """).param("v", v.id()).update();
        if (inserted == 1) {
            audit.record("DOCUMENT_ACK", "record", documentId.toString(), store.write(Map.of("version", number)));
        }
        return acks(documentId, number);
    }

    Acks acks(UUID documentId, int number) {
        Row doc = loadDocument(documentId, false);
        VRow v = versionRow(documentId, number);
        List<Reader> acked = store.jdbc().sql("""
                select a.user_id, coalesce(u.display_name, u.username) as name, a.acked_at
                from document_ack a join app_user u on u.id = a.user_id
                where a.version_id = :v order by a.acked_at
                """).param("v", v.id()).query((rs, i) -> new Reader(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class).toInstant())).list();
        Set<UUID> ackedIds = new java.util.HashSet<>();
        acked.forEach(a -> ackedIds.add(a.userId()));
        List<UUID> pendingIds = recipientIds(store.data(doc)).stream().filter(id -> !ackedIds.contains(id)).toList();
        List<Reader> pending = pendingIds.isEmpty() ? List.of() : store.jdbc().sql("""
                select id, coalesce(display_name, username) from app_user where id = any(:ids) order by 2
                """).param("ids", pendingIds.toArray(UUID[]::new))
                .query((rs, i) -> new Reader(rs.getObject(1, UUID.class), rs.getString(2), null)).list();
        return new Acks(number, acked, pending);
    }

    /** Destinataires : utilisateurs ayant l'un des rôles ou rattachés à l'un des sites. */
    List<UUID> recipientIds(Map<String, Object> data) {
        Object r = data.get("recipients");
        if (!(r instanceof Map<?, ?> m)) {
            return List.of();
        }
        String[] roles = strings(m.get("roles")).toArray(String[]::new);
        UUID[] sites = strings(m.get("siteIds")).stream().map(UUID::fromString).toArray(UUID[]::new);
        if (roles.length == 0 && sites.length == 0) {
            return List.of();
        }
        return store.jdbc().sql("""
                select u.id from app_user u
                where u.roles && cast(:roles as text[])
                   or exists (select 1 from user_site us where us.user_id = u.id and us.site_id = any(:sites))
                order by u.id
                """).param("roles", roles).param("sites", sites).query(UUID.class).list();
    }

    // ---------------------------------------------------------------- interne

    private static List<String> allowedRoles(Workflow.Transition t, Map<String, Object> data) {
        List<String> override = switch (t.from()) {
            case "VERIFICATION" -> strings(data.get("verifierRoles"));
            case "APPROBATION" -> strings(data.get("approverRoles"));
            default -> List.of();
        };
        return override.isEmpty() ? t.roles() : override;
    }

    private VersionView version(UUID documentId, int number) {
        Row doc = loadDocument(documentId, false);
        return version(versionRow(documentId, number), types.get(RecordService.DOCUMENT_TYPE).workflow(),
                store.data(doc), store.currentRoles());
    }

    private static VersionView version(VRow v, Workflow wf, Map<String, Object> data, Set<String> myRoles) {
        List<RecordDtos.AvailableTransition> available = OBSOLETE.equals(v.status()) ? List.of()
                : wf.from(v.status()).stream()
                .filter(t -> allowedRoles(t, data).stream().anyMatch(myRoles::contains))
                .map(t -> new RecordDtos.AvailableTransition(t.code(), t.label(), List.of())).toList();
        return new VersionView(v.id(), v.number(), v.status(), versionLabel(wf, v.status()), v.note(), v.fileName(),
                v.contentType(), v.size(), v.createdAt().toInstant(), v.createdByName(),
                v.diffusedAt() == null ? null : v.diffusedAt().toInstant(), DIFFUSED.equals(v.status()), available);
    }

    private static String versionLabel(Workflow wf, String status) {
        return OBSOLETE.equals(status) ? "Périmée" : DIFFUSED.equals(status) ? "En vigueur" : wf.label(status);
    }

    private List<VRow> versionRows(UUID documentId) {
        return store.jdbc().sql(V_SELECT + " where v.document_id = :d order by v.number desc")
                .param("d", documentId).query(VRow.class).list();
    }

    private VRow versionRow(UUID documentId, int number) {
        return store.jdbc().sql(V_SELECT + " where v.document_id = :d and v.number = :n")
                .param("d", documentId).param("n", number).query(VRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Version"));
    }

    private void setDocumentStatus(UUID documentId, String status) {
        store.jdbc().sql("update record set status = :s, updated_at = now() where id = :id")
                .param("s", status).param("id", documentId).update();
    }

    Row loadDocument(UUID id, boolean forUpdate) {
        Row r = records.load(id, forUpdate);
        if (!RecordService.DOCUMENT_TYPE.equals(r.typeCode())) {
            throw ApiException.notFound("Document");
        }
        return r;
    }

    /** PDF, DOCX ou XLSX, d'après le contenu (et l'extension pour distinguer DOCX / XLSX). */
    static String detectType(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Fichier vide");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Fichier trop volumineux (20 Mo maximum)");
        }
        if (startsWith(content, PDF)) {
            return PDF_TYPE;
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (startsWith(content, ZIP)) {
            String head = new String(content, 0, Math.min(content.length, 64 * 1024), StandardCharsets.ISO_8859_1);
            if (name.endsWith(".docx") || (!name.endsWith(".xlsx") && head.contains("word/"))) {
                return DOCX_TYPE;
            }
            if (name.endsWith(".xlsx") || head.contains("xl/")) {
                return XLSX_TYPE;
            }
        }
        throw ApiException.badRequest("Format non pris en charge (PDF, DOCX ou XLSX uniquement)");
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length && Arrays.equals(Arrays.copyOf(content, magic.length), magic);
    }

    private static void write(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "upload", ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(Object v) {
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
