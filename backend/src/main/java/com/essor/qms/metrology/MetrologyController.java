package com.essor.qms.metrology;

import com.essor.qms.records.AttachmentFiles;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.CurrentUser;
import com.essor.qms.shared.TabularFile.ImportReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API métrologie (contrat M4). */
@RestController
class MetrologyController {

    private final MetrologyService service;
    private final CurrentUser user;
    private final JdbcClient jdbc;
    private final Path root;

    MetrologyController(MetrologyService service, CurrentUser user, JdbcClient jdbc,
                        @Value("${essor.storage.dir}") Path storageRoot) {
        this.service = service;
        this.user = user;
        this.jdbc = jdbc;
        this.root = storageRoot.resolve("calibrations").normalize();
    }

    @GetMapping("/api/instruments")
    List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) UUID siteId,
                                   @RequestParam(defaultValue = "false") boolean selectable) {
        return service.list(q, status, siteId, selectable);
    }

    @PostMapping("/api/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody MetrologyService.InstrumentInput in) {
        return service.create(in);
    }

    @GetMapping("/api/instruments/{id}")
    Map<String, Object> detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PutMapping("/api/instruments/{id}")
    Map<String, Object> update(@PathVariable UUID id, @RequestBody MetrologyService.InstrumentInput in) {
        return service.update(id, in);
    }

    @PostMapping("/api/instruments/{id}/calibrations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> calibrate(@PathVariable UUID id, @RequestBody MetrologyService.CalibrationInput in) {
        return service.addCalibration(id, in);
    }

    @GetMapping("/api/instruments/{id}/calibrations")
    List<Map<String, Object>> calibrations(@PathVariable UUID id) {
        service.instrument(id);
        return service.calibrations(id);
    }

    @GetMapping("/api/instruments/{id}/impact")
    Map<String, Object> impact(@PathVariable UUID id,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate since) {
        return service.impact(id, since);
    }

    @PostMapping("/api/instruments/{id}/impact/quarantine")
    Map<String, Object> quarantine(@PathVariable UUID id,
                                   @RequestBody(required = false) MetrologyService.QuarantineInput in) {
        return service.quarantine(id, in);
    }

    @PostMapping(path = "/api/instruments/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importFile(@RequestPart("file") MultipartFile file,
                            @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        return service.importFile(file.getBytes(), dryRun);
    }

    /** Certificat d'étalonnage : PDF, JPEG ou PNG ≤ 10 Mo, type vérifié sur le contenu. */
    @PostMapping(path = "/api/calibrations/{id}/certificate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    Map<String, Object> certificate(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        user.requireWriter();
        service.calibration(id);
        if (file.getSize() > AttachmentFiles.MAX_BYTES) {
            throw AttachmentFiles.tooLarge();
        }
        byte[] content = file.getBytes();
        String type = AttachmentFiles.detectType(content, file.getContentType());
        UUID tenant = jdbc.sql("select app_current_tenant()").query(UUID.class).single();
        String relative = tenant + "/" + id;
        Path target = root.resolve(relative).normalize();
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "upload", ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        service.attachCertificate(id, AttachmentFiles.safeName(file.getOriginalFilename(), type), type,
                content.length, relative);
        return service.calibration(id);
    }

    @GetMapping("/api/calibrations/{id}/certificate")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> download(@PathVariable UUID id) {
        Map<String, Object> meta = service.certificateMeta(id);
        if (meta.get("path") == null) {
            throw ApiException.notFound("Certificat");
        }
        Path file = root.resolve((String) meta.get("path")).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Certificat");
        }
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) meta.get("contentType")))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename((String) meta.get("fileName"), java.nio.charset.StandardCharsets.UTF_8).build()
                            .toString())
                    .header("X-Content-Type-Options", "nosniff").body(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
