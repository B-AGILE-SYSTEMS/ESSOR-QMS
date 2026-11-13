package com.essor.qms.risks;

import com.essor.qms.shared.TabularFile.ImportReport;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/** API risques et opportunités (contrat M4). */
@RestController
class RiskController {

    private final RiskService risks;
    private final RiskScales scales;
    private final DuerPdf duer;

    RiskController(RiskService risks, RiskScales scales, DuerPdf duer) {
        this.risks = risks;
        this.scales = scales;
        this.duer = duer;
    }

    @GetMapping("/api/risk-scales")
    @Transactional
    List<RiskScales.Scale> scales() {
        return scales.all();
    }

    @PutMapping("/api/risk-scales/{code}")
    @Transactional
    RiskScales.Scale putScale(@PathVariable String code, @RequestBody RiskScales.ScaleInput in) {
        return scales.put(code, in);
    }

    @GetMapping("/api/risks")
    List<Map<String, Object>> list(@RequestParam(required = false) UUID siteId,
                                   @RequestParam(required = false) String type,
                                   @RequestParam(required = false) String level) {
        return risks.list(siteId, type, level);
    }

    @PostMapping("/api/risks")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody RiskService.RiskInput in) {
        return risks.create(in);
    }

    @GetMapping("/api/risks/matrix")
    Map<String, Object> matrix(@RequestParam(required = false) UUID siteId,
                               @RequestParam(required = false) String type,
                               @RequestParam(defaultValue = "residual") String basis,
                               @RequestParam(required = false) String scale) {
        return risks.matrix(siteId, type, basis, scale);
    }

    @GetMapping("/api/risks/duer.pdf")
    ResponseEntity<byte[]> duer(@RequestParam UUID siteId) {
        DuerPdf.Duer d = duer.load(siteId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("duer-" + d.siteCode().replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf").build()
                        .toString())
                .body(duer.render(d));
    }

    @PostMapping(path = "/api/risks/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importFile(@RequestPart("file") MultipartFile file,
                            @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        return risks.importFile(file.getBytes(), dryRun);
    }

    @GetMapping("/api/risks/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        return risks.get(id);
    }

    @PutMapping("/api/risks/{id}")
    Map<String, Object> update(@PathVariable UUID id, @RequestBody RiskService.RiskInput in) {
        return risks.update(id, in);
    }

    @PostMapping("/api/risks/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> addAction(@PathVariable UUID id, @RequestBody RiskService.ActionInput in) {
        return risks.addAction(id, in);
    }
}
