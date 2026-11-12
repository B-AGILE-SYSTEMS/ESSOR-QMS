package com.essor.qms.records;

import com.essor.qms.records.RecordDtos.CreateAction;
import com.essor.qms.records.RecordDtos.CreateRecord;
import com.essor.qms.records.RecordDtos.HistoryEntry;
import com.essor.qms.records.RecordDtos.Page;
import com.essor.qms.records.RecordDtos.RecordSummary;
import com.essor.qms.records.RecordDtos.RecordView;
import com.essor.qms.records.RecordDtos.TransitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/records")
@Validated
class RecordController {

    static final int MAX_PAGE_SIZE = 100;

    private final RecordService records;

    RecordController(RecordService records) {
        this.records = records;
    }

    @GetMapping
    Page<RecordSummary> list(@RequestParam(required = false) @Size(max = 30) String type,
                             @RequestParam(required = false) @Size(max = 30) String status,
                             @RequestParam(required = false) @Pattern(regexp = "^(mineure|majeure|critique)$") String severity,
                             @RequestParam(required = false) UUID siteId,
                             @RequestParam(required = false) @Size(max = 200) String q,
                             @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return records.list(type, status, severity, siteId, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecordView create(@Valid @RequestBody CreateRecord body) {
        return records.create(body);
    }

    @GetMapping("/{id}")
    RecordView get(@PathVariable UUID id) {
        return records.get(id);
    }

    @PatchMapping("/{id}")
    RecordView patch(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return records.patch(id, body);
    }

    @PostMapping("/{id}/transitions/{code}")
    RecordView transition(@PathVariable UUID id, @PathVariable @Size(max = 30) String code,
                          @Valid @RequestBody(required = false) TransitionRequest body) {
        return records.transition(id, code, body == null ? null : body.comment());
    }

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    RecordView addAction(@PathVariable UUID id, @Valid @RequestBody CreateAction body) {
        return records.addAction(id, body);
    }

    @GetMapping("/{id}/history")
    List<HistoryEntry> history(@PathVariable UUID id) {
        return records.history(id);
    }
}
