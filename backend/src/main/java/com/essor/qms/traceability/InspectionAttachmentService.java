package com.essor.qms.traceability;

import com.essor.qms.records.AttachmentFiles;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Photos (et pièces) d'un contrôle : mêmes règles que les pièces jointes M2 (JPEG, PNG ou PDF ≤ 10 Mo, type vérifié
 * sur le contenu, nom nettoyé), fichier sur disque ({@code essor.storage.dir}/inspections/&lt;tenant&gt;/&lt;id&gt;),
 * métadonnées en base sous RLS. Téléchargement par {@code GET /api/inspections/attachments/{id}}.
 */
@Service
@Transactional
class InspectionAttachmentService {

    record AttachmentView(UUID id, String fileName, String contentType, long size, Instant createdAt,
                          String createdByName) {
    }

    record Download(String fileName, String contentType, byte[] content) {
    }

    private static final String SELECT = """
            select a.id, a.file_name, a.content_type, a.size, a.created_at,
                   coalesce(u.display_name, u.username) created_by_name, a.storage_path
            from inspection_attachment a left join app_user u on u.id = a.created_by
            """;

    private final JdbcClient jdbc;
    private final TraceSupport support;
    private final AuditLog audit;
    private final Path root;

    InspectionAttachmentService(JdbcClient jdbc, TraceSupport support, AuditLog audit,
                                @Value("${essor.storage.dir}") Path storageRoot) {
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
        this.root = storageRoot.resolve("inspections").normalize();
    }

    AttachmentView add(UUID inspectionId, String originalName, String declaredType, byte[] content) {
        support.requireWriter();
        requireInspection(inspectionId);
        String type = AttachmentFiles.detectType(content, declaredType);
        UUID id = UUID.randomUUID();
        UUID tenant = jdbc.sql("select app_current_tenant()").query(UUID.class).single();
        String relative = tenant + "/" + id;
        write(root.resolve(relative), content);
        jdbc.sql("""
                insert into inspection_attachment (id, tenant_id, inspection_id, file_name, content_type, size,
                  storage_path, created_by)
                values (:id, app_current_tenant(), :insp, :name, :type, :size, :path, app_current_user())""")
                .param("id", id).param("insp", inspectionId).param("name", AttachmentFiles.safeName(originalName, type))
                .param("type", type).param("size", (long) content.length).param("path", relative).update();
        audit.record("INSPECTION_ATTACHMENT_ADDED", "inspection", inspectionId.toString(),
                "{\"attachmentId\":\"" + id + "\",\"size\":" + content.length + "}");
        return find(id).view();
    }

    @Transactional(readOnly = true)
    List<AttachmentView> list(UUID inspectionId) {
        requireInspection(inspectionId);
        return jdbc.sql(SELECT + " where a.inspection_id = :i order by a.created_at, a.id").param("i", inspectionId)
                .query(Row.class).list().stream().map(Row::view).toList();
    }

    @Transactional(readOnly = true)
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

    private record Row(UUID id, String fileName, String contentType, long size, java.time.OffsetDateTime createdAt,
                       String createdByName, String storagePath) {
        AttachmentView view() {
            return new AttachmentView(id, fileName, contentType, size, createdAt.toInstant(), createdByName);
        }
    }

    private Row find(UUID id) {
        return jdbc.sql(SELECT + " where a.id = :id").param("id", id).query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("Pièce jointe"));
    }

    private void requireInspection(UUID id) {
        if (jdbc.sql("select count(*) from inspection where id = :id").param("id", id).query(Long.class).single() == 0) {
            throw ApiException.notFound("Contrôle");
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
}
