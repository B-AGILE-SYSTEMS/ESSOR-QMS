package com.essor.qms.records;

import com.essor.qms.records.DocumentService.Acks;
import com.essor.qms.records.DocumentService.CreateDocument;
import com.essor.qms.records.DocumentService.Download;
import com.essor.qms.records.DocumentService.DocumentSummary;
import com.essor.qms.records.DocumentService.DocumentView;
import com.essor.qms.records.DocumentService.VersionView;
import com.essor.qms.records.RecordDtos.TransitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Gestion documentaire (contrat M3). */
@RestController
@RequestMapping("/api/documents")
@Validated
class DocumentController {

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    List<DocumentSummary> list(@RequestParam(required = false) @Size(max = 30) String status,
                               @RequestParam(required = false) @Size(max = 30) String type,
                               @RequestParam(required = false) @Size(max = 200) String q,
                               @RequestParam(required = false) Boolean inForce) {
        return documents.list(status, type, q, inForce);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView create(@RequestBody CreateDocument body) {
        return documents.create(body);
    }

    @GetMapping("/{id}")
    DocumentView get(@PathVariable UUID id) {
        return documents.get(id);
    }

    @PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    VersionView addVersion(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                           @RequestParam(required = false) String note) throws IOException {
        return documents.addVersion(id, file.getOriginalFilename(), file.getContentType(), file.getBytes(), note);
    }

    @PostMapping("/{id}/versions/{v}/transitions/{code}")
    VersionView transition(@PathVariable UUID id, @PathVariable int v, @PathVariable @Size(max = 30) String code,
                           @Valid @RequestBody(required = false) TransitionRequest body) {
        return documents.transition(id, v, code, body == null ? null : body.comment());
    }

    @GetMapping("/{id}/versions/{v}/file")
    ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable int v,
                                @RequestParam(defaultValue = "false") boolean download) {
        Download d = documents.download(id, v);
        ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(d.fileName(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff").body(d.content());
    }

    @PostMapping("/{id}/versions/{v}/ack")
    Acks ack(@PathVariable UUID id, @PathVariable int v) {
        return documents.ack(id, v);
    }

    @GetMapping("/{id}/versions/{v}/acks")
    Acks acks(@PathVariable UUID id, @PathVariable int v) {
        return documents.acks(id, v);
    }
}
