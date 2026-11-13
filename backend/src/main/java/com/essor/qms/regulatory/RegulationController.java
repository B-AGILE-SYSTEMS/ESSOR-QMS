package com.essor.qms.regulatory;

import com.essor.qms.shared.TabularFile.ImportReport;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/** API veille réglementaire (contrat M4). */
@RestController
class RegulationController {

    private final RegulationService service;

    RegulationController(RegulationService service) {
        this.service = service;
    }

    @GetMapping("/api/regulations")
    List<Map<String, Object>> list(@RequestParam(required = false) String domain,
                                   @RequestParam(required = false) UUID siteId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String q) {
        return service.list(domain, siteId, status, q);
    }

    @PostMapping("/api/regulations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody RegulationService.RegulationInput in) {
        return service.create(in);
    }

    @GetMapping("/api/regulations/compliance")
    Map<String, Object> compliance(@RequestParam(required = false) UUID siteId) {
        return service.compliance(siteId);
    }

    @PostMapping(path = "/api/regulations/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importFile(@RequestPart("file") MultipartFile file,
                            @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        return service.importFile(file.getBytes(), dryRun);
    }

    @GetMapping("/api/regulations/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/api/regulations/{id}")
    Map<String, Object> update(@PathVariable UUID id, @RequestBody RegulationService.RegulationInput in) {
        return service.update(id, in);
    }

    @PutMapping("/api/regulations/{id}/applicability")
    Map<String, Object> applicability(@PathVariable UUID id,
                                      @RequestBody List<RegulationService.Applicability> items) {
        return service.setApplicability(id, items);
    }

    @PostMapping("/api/regulations/{id}/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> evaluate(@PathVariable UUID id, @RequestBody RegulationService.EvaluationInput in) {
        return service.evaluate(id, in);
    }
}
