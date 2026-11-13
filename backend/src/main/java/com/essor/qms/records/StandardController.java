package com.essor.qms.records;

import com.essor.qms.records.StandardImport.ImportReport;
import com.essor.qms.records.StandardService.RequirementDetail;
import com.essor.qms.records.StandardService.RequirementNode;
import com.essor.qms.records.StandardService.RequirementRef;
import com.essor.qms.records.StandardService.StandardView;
import com.essor.qms.shared.ApiException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Référentiels : import (xlsx / csv), arbre des exigences, liens fiche ↔ exigences (contrat M3). */
@RestController
class StandardController {

    private static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;

    private final StandardService standards;

    StandardController(StandardService standards) {
        this.standards = standards;
    }

    @GetMapping("/api/standards")
    List<StandardView> list() {
        return standards.list();
    }

    @GetMapping("/api/standards/{id}/requirements")
    List<RequirementNode> tree(@PathVariable UUID id) {
        return standards.tree(id);
    }

    @GetMapping("/api/requirements/{id}")
    RequirementDetail requirement(@PathVariable UUID id) {
        return standards.requirement(id);
    }

    @PostMapping(path = "/api/standards/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importFile(@RequestPart("file") MultipartFile file,
                            @RequestParam(required = false) UUID standardId,
                            @RequestParam(required = false) String standardCode,
                            @RequestParam(required = false) String standardVersion,
                            @RequestParam(required = false) String standardTitle,
                            @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "too_large",
                    "Fichier trop volumineux (5 Mo maximum)");
        }
        var lines = StandardImport.parse(file.getBytes(), file.getOriginalFilename());
        return standards.importLines(standardId, strip(standardCode), strip(standardVersion), strip(standardTitle),
                lines, dryRun);
    }

    @GetMapping("/api/standards/import/template")
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) {
        boolean csv = "csv".equalsIgnoreCase(format);
        return ResponseEntity.ok()
                .contentType(csv ? new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)
                        : MediaType.parseMediaType(StandardImport.XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(csv ? "modele-referentiel.csv" : "modele-referentiel.xlsx").build().toString())
                .body(csv ? StandardImport.templateCsv() : StandardImport.templateXlsx());
    }

    @GetMapping("/api/records/{id}/requirements")
    List<RequirementRef> links(@PathVariable UUID id) {
        return standards.links(id);
    }

    @PutMapping("/api/records/{id}/requirements")
    List<RequirementRef> replaceLinks(@PathVariable UUID id, @RequestBody List<UUID> requirementIds) {
        return standards.replaceLinks(id, requirementIds);
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }
}
