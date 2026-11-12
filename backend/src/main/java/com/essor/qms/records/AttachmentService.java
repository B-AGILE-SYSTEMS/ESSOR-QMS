package com.essor.qms.records;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pièces jointes des fiches : JPEG, PNG ou PDF de 10 Mo au plus, type vérifié sur le contenu réel.
 * Fichiers sur disque local ({@code essor.storage.dir}/attachments/&lt;tenant&gt;/&lt;id&gt;), métadonnées en base (RLS).
 */
@Service
@Transactional
class AttachmentService {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_NAME = 200;
    private static final Set<String> GENERIC_TYPES = Set.of("application/octet-stream", "binary/octet-stream");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    record AttachmentView(UUID id, String fileName, String contentType, long size, Instant createdAt,
                          String createdByName) {
    }

    record Download(String fileName, String contentType, byte[] content) {
    }

    private record Row(UUID id, String fileName, String contentType, long size, OffsetDateTime createdAt,
                       String createdByName, String storagePath) {
    }

    private static final String SELECT = """
            select a.id, a.file_name, a.content_type, a.size, a.created_at,
                   coalesce(u.display_name, u.username) as created_by_name, a.storage_path
            from attachment a left join app_user u on u.id = a.created_by
            """;

    private final RecordStore store;
    private final AuditLog audit;
    private final Path root;

    AttachmentService(RecordStore store, AuditLog audit, @Value("${essor.storage.dir}") Path storageRoot) {
        this.store = store;
        this.audit = audit;
        this.root = storageRoot.resolve("attachments").normalize();
    }

    AttachmentView add(UUID recordId, String originalName, String declaredType, byte[] content) {
        requireWriter();
        requireRecord(recordId);
        String type = detectType(content, declaredType);
        UUID id = UUID.randomUUID();
        UUID tenantId = store.jdbc().sql("select app_current_tenant()").query(UUID.class).single();
        String relative = tenantId + "/" + id;
        write(root.resolve(relative), content);
        store.jdbc().sql("""
                insert into attachment (id, tenant_id, record_id, file_name, content_type, size, storage_path,
                                        created_by)
                values (:id, app_current_tenant(), :record, :name, :type, :size, :path, app_current_user())
                """).param("id", id).param("record", recordId).param("name", safeName(originalName, type))
                .param("type", type).param("size", (long) content.length).param("path", relative).update();
        store.jdbc().sql("update record set updated_at = now() where id = :id").param("id", recordId).update();
        audit.record("ATTACHMENT_ADDED", "record", recordId.toString(),
                store.write(Map.of("attachmentId", id, "contentType", type, "size", content.length)));
        return view(find(id));
    }

    List<AttachmentView> list(UUID recordId) {
        requireRecord(recordId);
        return store.jdbc().sql(SELECT + " where a.record_id = :r order by a.created_at, a.id")
                .param("r", recordId).query(Row.class).list().stream().map(AttachmentService::view).toList();
    }

    Download download(UUID id) {
        Row row = find(id);
        Path file = root.resolve(row.storagePath()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Pièce jointe");
        }
        try {
            return new Download(row.fileName(), row.contentType(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Type réel d'après les premiers octets ; refus si vide, trop gros, autre format ou type déclaré contradictoire. */
    static String detectType(byte[] content, String declaredType) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Fichier vide");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
        }
        String detected = startsWith(content, PNG) ? "image/png"
                : startsWith(content, JPEG) ? "image/jpeg"
                : startsWith(content, PDF) ? "application/pdf" : null;
        if (detected == null) {
            throw ApiException.badRequest("Format non pris en charge (JPEG, PNG ou PDF uniquement)");
        }
        String declared = declaredType == null ? "" : declaredType.split(";")[0].strip().toLowerCase();
        if (!declared.isEmpty() && !GENERIC_TYPES.contains(declared)
                && !declared.equals(detected) && !("image/jpg".equals(declared) && "image/jpeg".equals(detected))) {
            throw ApiException.badRequest("Le contenu du fichier ne correspond pas au type annoncé (" + declared + ")");
        }
        return detected;
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Fichier trop volumineux (10 Mo maximum)");
    }

    /** Nom affiché : sans chemin ni caractère de contrôle, 200 caractères au plus. */
    static String safeName(String original, String type) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty()) {
            name = "piece-jointe." + switch (type) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                default -> "pdf";
            };
        }
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }

    private Row find(UUID id) {
        return store.jdbc().sql(SELECT + " where a.id = :id").param("id", id).query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("Pièce jointe"));
    }

    private void requireRecord(UUID recordId) {
        if (store.find(recordId, false).isEmpty()) {
            throw ApiException.notFound("Fiche");
        }
    }

    private void requireWriter() {
        Set<String> roles = store.currentRoles();
        if (roles.isEmpty() || roles.equals(Set.of("READER"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Accès en lecture seule");
        }
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

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length && Arrays.equals(Arrays.copyOf(content, magic.length), magic);
    }

    private static AttachmentView view(Row r) {
        return new AttachmentView(r.id(), r.fileName(), r.contentType(), r.size(), r.createdAt().toInstant(),
                r.createdByName());
    }
}
