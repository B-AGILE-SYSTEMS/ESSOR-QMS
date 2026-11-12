package com.essor.qms.records;

import com.essor.qms.records.AttachmentService.AttachmentView;
import com.essor.qms.records.AttachmentService.Download;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Pièces jointes des fiches (contrat M2). */
@RestController
class AttachmentController {

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(path = "/api/records/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AttachmentView add(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        if (file.getSize() > AttachmentService.MAX_BYTES) {
            throw AttachmentService.tooLarge();
        }
        try {
            return attachments.add(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/api/records/{id}/attachments")
    List<AttachmentView> list(@PathVariable UUID id) {
        return attachments.list(id);
    }

    /** {@code inline} par défaut (aperçu), {@code ?download=true} pour forcer l'enregistrement. */
    @GetMapping("/api/attachments/{id}")
    ResponseEntity<byte[]> download(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "false") boolean download) {
        Download d = attachments.download(id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(d.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(d.content());
    }
}
