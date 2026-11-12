package com.essor.qms.records;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Types de fiche du tenant courant (RLS) et leur workflow. */
@Service
class RecordTypes {

    record RecordType(String code, String label, String refPrefix, JsonNode fieldSchema, Workflow workflow) {

        /** Libellé d'un champ (colonne commune ou champ personnalisé du schéma). */
        String fieldLabel(String field) {
            String common = COMMON_LABELS.get(field);
            if (common != null) {
                return common;
            }
            if (fieldSchema != null && fieldSchema.path("fields").isArray()) {
                for (JsonNode f : fieldSchema.path("fields")) {
                    if (field.equals(f.path("code").asText())) {
                        return f.path("label").asText(field);
                    }
                }
            }
            return field;
        }
    }

    static final Map<String, String> COMMON_LABELS = Map.of(
            "title", "titre", "description", "description", "severity", "gravité",
            "siteId", "site", "ownerId", "responsable", "dueDate", "échéance");

    private record Row(String code, String label, String refPrefix, String fieldSchema, String workflow) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditLog audit;

    RecordTypes(JdbcClient jdbc, ObjectMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    /** Types du tenant ; copie les modèles par défaut manquants au premier usage (nouveau tenant, nouveau type). */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<String, RecordType> all() {
        List<Row> rows = load();
        if (missingTemplates() > 0) {
            jdbc.sql("""
                    insert into record_type (tenant_id, code, label, ref_prefix, field_schema, workflow)
                    select app_current_tenant(), code, label, ref_prefix, field_schema, workflow
                    from record_type_template
                    where app_current_tenant() is not null
                    on conflict do nothing
                    """).update();
            rows = load();
        }
        Map<String, RecordType> types = new LinkedHashMap<>();
        for (Row r : rows) {
            types.put(r.code(), new RecordType(r.code(), r.label(), r.refPrefix(), read(r.fieldSchema()),
                    parseWorkflow(r.workflow())));
        }
        return types;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<RecordType> find(String code) {
        return Optional.ofNullable(all().get(code));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    RecordType get(String code) {
        return find(code).orElseThrow(() -> ApiException.notFound("Type de fiche"));
    }

    /** Remplace le workflow après validation ; refuse de retirer un état encore utilisé par des fiches. */
    @Transactional(propagation = Propagation.MANDATORY)
    Workflow updateWorkflow(String code, JsonNode body) {
        RecordType type = get(code);
        Workflow wf;
        try {
            wf = json.treeToValue(body, Workflow.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid(List.of("JSON de workflow mal formé"));
        }
        List<String> errors = wf == null ? List.of("workflow vide") : wf.validate();
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        List<String> used = jdbc.sql("select distinct status from record where type_code = :t")
                .param("t", code).query(String.class).list();
        List<String> orphans = new ArrayList<>(used.stream().filter(s -> wf.state(s).isEmpty()).toList());
        if (!orphans.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "state_in_use",
                    "Des fiches sont encore dans les statuts : " + String.join(", ", orphans),
                    Map.of("states", orphans));
        }
        jdbc.sql("update record_type set workflow = cast(:wf as jsonb), updated_at = now() where code = :t")
                .param("wf", write(wf)).param("t", code).update();
        audit.record("WORKFLOW_UPDATED", "record_type", code, null);
        return wf;
    }

    private static ApiException invalid(List<String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_workflow",
                "Workflow invalide : " + String.join(" ; ", errors), Map.of("errors", errors));
    }

    private int missingTemplates() {
        return jdbc.sql("""
                select count(*) from record_type_template tt
                where not exists (select 1 from record_type r where r.code = tt.code)
                """).query(Integer.class).single();
    }

    private List<Row> load() {
        return jdbc.sql("""
                select code, label, ref_prefix, field_schema::text as field_schema, workflow::text as workflow
                from record_type order by code
                """).query(Row.class).list();
    }

    private Workflow parseWorkflow(String text) {
        try {
            return json.readValue(text, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Workflow stocké illisible", e);
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Schéma stocké illisible", e);
        }
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
