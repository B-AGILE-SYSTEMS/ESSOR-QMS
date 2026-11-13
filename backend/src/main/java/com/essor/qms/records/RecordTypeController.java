package com.essor.qms.records;

import com.essor.qms.records.RecordDtos.DashboardSummary;
import com.essor.qms.records.RecordDtos.RecordSummary;
import com.essor.qms.records.RecordTypes.RecordType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Types de fiche et workflows, « Mes actions », tableau de bord. */
@RestController
class RecordTypeController {

    private static final int MAX_TASKS = 100;

    record TypeView(String code, String label, String refPrefix, JsonNode fieldSchema, Workflow workflow) {
        static TypeView of(RecordType t) {
            return new TypeView(t.code(), t.label(), t.refPrefix(), t.fieldSchema(), t.workflow());
        }
    }

    private final RecordTypes types;
    private final RecordService records;
    private final JdbcClient jdbc;
    private final List<DashboardKpis> kpis;

    RecordTypeController(RecordTypes types, RecordService records, JdbcClient jdbc, List<DashboardKpis> kpis) {
        this.kpis = kpis;
        this.types = types;
        this.records = records;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/record-types")
    @Transactional
    List<TypeView> list() {
        return types.all().values().stream().map(TypeView::of).toList();
    }

    @GetMapping("/api/record-types/{code}/workflow")
    @Transactional
    Workflow workflow(@PathVariable String code) {
        return types.get(code).workflow();
    }

    @PutMapping("/api/record-types/{code}/workflow")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Workflow updateWorkflow(@PathVariable String code, @RequestBody JsonNode body) {
        return types.updateWorkflow(code, body);
    }

    @GetMapping("/api/my-tasks")
    List<RecordSummary> myTasks() {
        return records.myTasks(MAX_TASKS);
    }

    @GetMapping("/api/dashboard/summary")
    @Transactional
    DashboardSummary dashboard() {
        Map<String, RecordType> all = types.all();
        Workflow nc = all.containsKey("NC") ? all.get("NC").workflow() : null;
        String[] ncFinal = nc == null ? new String[0] : nc.finalStates().toArray(String[]::new);
        long ncOpen = jdbc.sql("select count(*) from record where type_code = 'NC' and status <> all(cast(:f as text[]))")
                .param("f", ncFinal).query(Long.class).single();
        long toQualify = nc == null ? 0 : jdbc.sql("select count(*) from record where type_code = 'NC' and status = :s")
                .param("s", nc.initial()).query(Long.class).single();
        java.util.Set<String> done = RecordService.doneStates(all);
        all.values().forEach(t -> done.addAll(t.workflow().finalStates()));
        long overdue = jdbc.sql("""
                select count(*) from record
                where type_code = 'ACTION' and due_date < :today and status <> all(cast(:done as text[]))
                """).param("today", LocalDate.now(RecordService.ZONE)).param("done", done.toArray(String[]::new))
                .query(Long.class).single();
        BigDecimal cost = jdbc.sql("""
                select coalesce(sum(case when data->>'cost' ~ '^[0-9]{1,12}(\\.[0-9]+)?$'
                                         then (data->>'cost')::numeric end), 0)
                from record
                where type_code = 'NC' and created_at >= date_trunc('month', now())
                """).query(BigDecimal.class).single();
        Map<String, Object> extra = new java.util.HashMap<>();
        kpis.forEach(k -> extra.putAll(k.kpis()));
        Object rate = extra.get("regulatoryCompliance");
        return new DashboardSummary(ncOpen, toQualify, overdue, cost,
                rate instanceof Number n ? n.doubleValue() : null,
                extra.get("calibrationsDue15") instanceof Number n ? n.longValue() : 0,
                extra.get("calibrationsOverdue") instanceof Number n ? n.longValue() : 0);
    }
}
