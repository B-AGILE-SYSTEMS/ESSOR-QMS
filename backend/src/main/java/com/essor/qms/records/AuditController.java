package com.essor.qms.records;

import com.essor.qms.records.AuditService.AnswerRequest;
import com.essor.qms.records.AuditService.AuditDetail;
import com.essor.qms.records.AuditService.GenerateRequest;
import com.essor.qms.records.AuditService.ProgramItem;
import com.essor.qms.records.AuditService.Question;
import com.essor.qms.records.AuditService.QuestionRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Audits (contrat M3) : programme, grille, réponses, rapport PDF. Création et workflow : API des fiches. */
@RestController
@RequestMapping("/api/audits")
@Validated
class AuditController {

    private final AuditService audits;
    private final AuditReportPdf pdf;

    AuditController(AuditService audits, AuditReportPdf pdf) {
        this.audits = audits;
        this.pdf = pdf;
    }

    @GetMapping
    List<ProgramItem> program(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
                              @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 200) String status,
                              @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "^me$") String auditor) {
        List<String> statuses = status == null || status.isBlank() ? List.of()
                : java.util.Arrays.stream(status.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        return audits.program(year == null ? LocalDate.now(RecordService.ZONE).getYear() : year, statuses,
                "me".equals(auditor));
    }

    @GetMapping("/{id}")
    AuditDetail detail(@PathVariable UUID id) {
        return audits.detail(id);
    }

    @GetMapping("/{id}/checklist")
    List<Question> checklist(@PathVariable UUID id) {
        return audits.questions(id);
    }

    @PostMapping("/{id}/checklist/generate")
    List<Question> generate(@PathVariable UUID id, @RequestBody GenerateRequest body) {
        return audits.generate(id, body);
    }

    @PostMapping("/{id}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    Question addQuestion(@PathVariable UUID id, @RequestBody QuestionRequest body) {
        return audits.addQuestion(id, body);
    }

    @PatchMapping("/{id}/questions/{questionId}")
    Question updateQuestion(@PathVariable UUID id, @PathVariable UUID questionId, @RequestBody QuestionRequest body) {
        return audits.updateQuestion(id, questionId, body);
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteQuestion(@PathVariable UUID id, @PathVariable UUID questionId) {
        audits.deleteQuestion(id, questionId);
    }

    @PutMapping("/{id}/answers/{questionId}")
    Question answer(@PathVariable UUID id, @PathVariable UUID questionId, @RequestBody AnswerRequest body) {
        return audits.answer(id, questionId, body);
    }

    @GetMapping("/{id}/report.pdf")
    ResponseEntity<byte[]> report(@PathVariable UUID id) {
        byte[] content = pdf.render(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(pdf.fileName(id)).build().toString())
                .body(content);
    }
}
