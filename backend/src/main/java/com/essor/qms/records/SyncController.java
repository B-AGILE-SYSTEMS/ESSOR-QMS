package com.essor.qms.records;

import com.essor.qms.records.RecordDtos.SyncChanges;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Synchronisation du mobile : fiches modifiées depuis le dernier appel (contrat M2). */
@RestController
class SyncController {

    private final RecordService records;

    SyncController(RecordService records) {
        this.records = records;
    }

    @GetMapping("/api/sync/changes")
    SyncChanges changes(@RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return records.changes(since);
    }
}
