package com.essor.qms.records;

import com.essor.qms.records.StandardImport.ImportLine;
import com.essor.qms.records.StandardImport.ImportReport;
import com.essor.qms.records.StandardImport.LineError;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Référentiels (normes) du tenant : import, arborescence des exigences, liens fiche ↔ exigences. */
@Service
@Transactional
class StandardService {

    static final Pattern STANDARD_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,29}$");
    static final Pattern REQUIREMENT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,29}$");
    static final int MAX_LINES = 5000;
    private static final int MAX_LINKS = 200;

    record StandardView(UUID id, String code, String version, String title, long requirementCount) {
    }

    record RequirementNode(UUID id, String code, String title, String text, boolean auditable, int position,
                           List<RequirementNode> children) {
    }

    record RequirementRef(UUID id, String code, String title, UUID standardId, String standardCode) {
    }

    record LinkedRecord(UUID id, String reference, String type, String status, String title) {
    }

    record Finding(UUID auditId, String auditReference, UUID questionId, String result, String comment,
                   UUID ncId) {
    }

    record RequirementDetail(UUID id, String code, String title, String text, boolean auditable,
                             StandardView standard, RequirementRef parent, List<RequirementRef> children,
                             List<LinkedRecord> documents, List<LinkedRecord> records, List<Finding> findings) {
    }

    private record ReqRow(UUID id, String code, String title, String text, UUID parentId, int position,
                          boolean auditable) {
    }

    private final RecordStore store;
    private final RecordService records;
    private final AuditLog audit;

    StandardService(RecordStore store, RecordService records, AuditLog audit) {
        this.store = store;
        this.records = records;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- lecture

    List<StandardView> list() {
        return store.jdbc().sql("""
                select s.id, s.code, s.version, s.title,
                       (select count(*) from requirement r where r.standard_id = s.id) as requirement_count
                from standard s order by s.code, s.version
                """).query(StandardView.class).list();
    }

    StandardView standard(UUID id) {
        return store.jdbc().sql("""
                select s.id, s.code, s.version, s.title,
                       (select count(*) from requirement r where r.standard_id = s.id) as requirement_count
                from standard s where s.id = :id
                """).param("id", id).query(StandardView.class).optional()
                .orElseThrow(() -> ApiException.notFound("Référentiel"));
    }

    /** Arbre des exigences (racines dans l'ordre d'import, enfants imbriqués). */
    List<RequirementNode> tree(UUID standardId) {
        standard(standardId);
        List<ReqRow> rows = store.jdbc().sql("""
                select id, code, title, text, parent_id, position, auditable
                from requirement where standard_id = :s order by position, code
                """).param("s", standardId).query(ReqRow.class).list();
        Map<UUID, List<ReqRow>> byParent = new HashMap<>();
        rows.forEach(r -> byParent.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r));
        return build(byParent, null, 0);
    }

    private static List<RequirementNode> build(Map<UUID, List<ReqRow>> byParent, UUID parent, int depth) {
        if (depth > 20) {
            return List.of();
        }
        return byParent.getOrDefault(parent, List.of()).stream()
                .map(r -> new RequirementNode(r.id(), r.code(), r.title(), r.text(), r.auditable(), r.position(),
                        build(byParent, r.id(), depth + 1)))
                .toList();
    }

    RequirementDetail requirement(UUID id) {
        record Row(UUID id, String code, String title, String text, boolean auditable, UUID standardId,
                   UUID parentId) {
        }
        Row r = store.jdbc().sql("""
                select id, code, title, text, auditable, standard_id, parent_id from requirement where id = :id
                """).param("id", id).query(Row.class).optional()
                .orElseThrow(() -> ApiException.notFound("Exigence"));
        RequirementRef parent = r.parentId() == null ? null : refs(List.of(r.parentId())).stream().findFirst()
                .orElse(null);
        List<RequirementRef> children = store.jdbc().sql(REF_SELECT + " where r.parent_id = :p order by r.position")
                .param("p", id).query(RequirementRef.class).list();
        List<LinkedRecord> linked = store.jdbc().sql("""
                select rec.id, rec.reference, rec.type_code as type, rec.status, rec.title
                from record_requirement rr join record rec on rec.id = rr.record_id
                where rr.requirement_id = :id order by rec.created_at desc
                """).param("id", id).query(LinkedRecord.class).list();
        List<Finding> findings = store.jdbc().sql("""
                select q.audit_id, a.reference as audit_reference, q.id as question_id, q.result, q.comment, q.nc_id
                from audit_question q join record a on a.id = q.audit_id
                where q.requirement_id = :id and q.result is not null order by a.created_at desc
                """).param("id", id).query(Finding.class).list();
        return new RequirementDetail(r.id(), r.code(), r.title(), r.text(), r.auditable(), standard(r.standardId()),
                parent, children,
                linked.stream().filter(l -> RecordService.DOCUMENT_TYPE.equals(l.type())).toList(),
                linked.stream().filter(l -> !RecordService.DOCUMENT_TYPE.equals(l.type())).toList(),
                findings);
    }

    private static final String REF_SELECT = """
            select r.id, r.code, r.title, r.standard_id, s.code || ':' || s.version as standard_code
            from requirement r join standard s on s.id = r.standard_id
            """;

    List<RequirementRef> refs(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return store.jdbc().sql(REF_SELECT + " where r.id = any(:ids) order by s.code, r.position")
                .param("ids", ids.toArray(UUID[]::new)).query(RequirementRef.class).list();
    }

    // ---------------------------------------------------------------- liens fiche ↔ exigences

    List<RequirementRef> links(UUID recordId) {
        records.load(recordId, false);
        List<UUID> ids = store.jdbc().sql("select requirement_id from record_requirement where record_id = :r")
                .param("r", recordId).query(UUID.class).list();
        return refs(ids);
    }

    List<RequirementRef> replaceLinks(UUID recordId, List<UUID> requirementIds) {
        records.requireWriter();
        records.load(recordId, true);
        Set<UUID> wanted = new LinkedHashSet<>(requirementIds == null ? List.of() : requirementIds);
        wanted.remove(null);
        if (wanted.size() > MAX_LINKS) {
            throw ApiException.badRequest("Au plus " + MAX_LINKS + " exigences par fiche");
        }
        List<RequirementRef> found = refs(List.copyOf(wanted));
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("Exigence inconnue dans ce tenant");
        }
        store.jdbc().sql("delete from record_requirement where record_id = :r").param("r", recordId).update();
        for (UUID req : wanted) {
            link(recordId, req);
        }
        store.jdbc().sql("update record set updated_at = now() where id = :id").param("id", recordId).update();
        audit.record("REQUIREMENTS_LINKED", "record", recordId.toString(),
                store.write(Map.of("requirementIds", wanted)));
        return found;
    }

    void link(UUID recordId, UUID requirementId) {
        store.jdbc().sql("""
                insert into record_requirement (tenant_id, record_id, requirement_id)
                values (app_current_tenant(), :r, :q) on conflict do nothing
                """).param("r", recordId).param("q", requirementId).update();
    }

    // ---------------------------------------------------------------- import

    /**
     * Importe les lignes dans le référentiel (créé s'il n'existe pas). Tout ou rien : à la moindre erreur,
     * rien n'est écrit et le rapport liste les erreurs ligne par ligne. {@code dryRun} : simulation seule.
     */
    ImportReport importLines(UUID standardId, String code, String version, String title, List<ImportLine> lines,
                             boolean dryRun) {
        requireManager();
        UUID existingId = standardId != null ? standard(standardId).id() : findStandard(code, version, title);
        Map<String, UUID> existing = new HashMap<>();
        if (existingId != null) {
            store.jdbc().sql("select code, id from requirement where standard_id = :s").param("s", existingId)
                    .query((rs, i) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class))).list()
                    .forEach(e -> existing.put(e.getKey(), e.getValue()));
        }
        List<LineError> errors = validate(lines, existing.keySet());
        int created = (int) lines.stream().filter(l -> l.code() != null && !existing.containsKey(l.code())).count();
        int updated = lines.size() - created;
        if (!errors.isEmpty() || dryRun) {
            return new ImportReport(existingId, created, updated, errors, false, dryRun);
        }
        UUID sid = existingId != null ? existingId : createStandard(code, version, title);
        Map<String, UUID> ids = new HashMap<>(existing);
        for (ImportLine l : lines) {
            ids.computeIfAbsent(l.code(), k -> UUID.randomUUID());
        }
        // insertion sans parent, puis parents (l'ordre du fichier n'impose pas parent avant enfant)
        int position = 0;
        for (ImportLine l : lines) {
            store.jdbc().sql("""
                    insert into requirement (id, tenant_id, standard_id, code, title, text, position, auditable)
                    values (:id, app_current_tenant(), :s, :code, :title, :text, :pos, :aud)
                    on conflict (tenant_id, standard_id, code) do update
                       set title = excluded.title, text = excluded.text, position = excluded.position,
                           auditable = excluded.auditable
                    """).param("id", ids.get(l.code())).param("s", sid).param("code", l.code())
                    .param("title", l.title()).param("text", l.text()).param("pos", position++)
                    .param("aud", l.auditable()).update();
        }
        for (ImportLine l : lines) {
            store.jdbc().sql("update requirement set parent_id = :p where id = :id")
                    .param("p", l.parent() == null ? null : ids.get(l.parent())).param("id", ids.get(l.code()))
                    .update();
        }
        store.jdbc().sql("update standard set updated_at = now() where id = :id").param("id", sid).update();
        audit.record("STANDARD_IMPORTED", "standard", sid.toString(),
                store.write(Map.of("created", created, "updated", updated)));
        return new ImportReport(sid, created, updated, List.of(), true, false);
    }

    private UUID findStandard(String code, String version, String title) {
        if (code == null || !STANDARD_CODE.matcher(code).matches()) {
            throw ApiException.badRequest("standardCode obligatoire (lettres, chiffres, . _ -, 30 caractères max)");
        }
        if (version == null || version.isBlank() || version.length() > 20) {
            throw ApiException.badRequest("standardVersion obligatoire (20 caractères max)");
        }
        UUID id = store.jdbc().sql("select id from standard where code = :c and version = :v")
                .param("c", code).param("v", version).query(UUID.class).optional().orElse(null);
        if (id == null && (title == null || title.isBlank() || title.length() > 200)) {
            throw ApiException.badRequest("standardTitle obligatoire pour un nouveau référentiel (200 caractères max)");
        }
        return id;
    }

    private UUID createStandard(String code, String version, String title) {
        UUID id = UUID.randomUUID();
        store.jdbc().sql("""
                insert into standard (id, tenant_id, code, version, title)
                values (:id, app_current_tenant(), :c, :v, :t)
                """).param("id", id).param("c", code).param("v", version).param("t", title.strip()).update();
        audit.record("STANDARD_CREATED", "standard", id.toString(), null);
        return id;
    }

    /** Contrôles ligne par ligne : champs, doublons, parent connu, absence de cycle. */
    static List<LineError> validate(List<ImportLine> lines, Set<String> existingCodes) {
        List<LineError> errors = new ArrayList<>();
        if (lines.isEmpty()) {
            errors.add(new LineError(1, "Aucune exigence dans le fichier"));
            return errors;
        }
        if (lines.size() > MAX_LINES) {
            errors.add(new LineError(1, "Au plus " + MAX_LINES + " lignes par import"));
            return errors;
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (ImportLine l : lines) {
            l.errors().forEach(m -> errors.add(new LineError(l.line(), m)));
            if (l.code() == null || l.code().isEmpty()) {
                errors.add(new LineError(l.line(), "code obligatoire"));
            } else if (!REQUIREMENT_CODE.matcher(l.code()).matches()) {
                errors.add(new LineError(l.line(), "code invalide (lettres, chiffres, . _ -, 30 caractères max)"));
            } else if (seen.putIfAbsent(l.code(), l.line()) != null) {
                errors.add(new LineError(l.line(), "code " + l.code() + " en double (déjà ligne "
                        + seen.get(l.code()) + ")"));
            }
            if (l.title() == null || l.title().isEmpty()) {
                errors.add(new LineError(l.line(), "titre obligatoire"));
            } else if (l.title().length() > 300) {
                errors.add(new LineError(l.line(), "titre trop long (300 caractères max)"));
            }
            if (l.text() != null && l.text().length() > 10000) {
                errors.add(new LineError(l.line(), "texte trop long (10 000 caractères max)"));
            }
        }
        Map<String, String> parentOf = new HashMap<>();
        for (ImportLine l : lines) {
            if (l.parent() == null || l.code() == null) {
                continue;
            }
            if (l.parent().equals(l.code())) {
                errors.add(new LineError(l.line(), "une exigence ne peut pas être son propre parent"));
            } else if (!seen.containsKey(l.parent()) && !existingCodes.contains(l.parent())) {
                errors.add(new LineError(l.line(), "parent " + l.parent() + " inconnu"));
            } else {
                parentOf.put(l.code(), l.parent());
            }
        }
        for (ImportLine l : lines) {
            if (l.code() != null && inCycle(l.code(), parentOf)) {
                errors.add(new LineError(l.line(), "cycle dans les parents de " + l.code()));
            }
        }
        errors.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return errors;
    }

    private static boolean inCycle(String code, Map<String, String> parentOf) {
        Set<String> visited = new HashSet<>();
        String current = parentOf.get(code);
        while (current != null) {
            if (current.equals(code)) {
                return true;
            }
            if (!visited.add(current)) {
                return false;
            }
            current = parentOf.get(current);
        }
        return false;
    }

    private void requireManager() {
        Set<String> roles = store.currentRoles();
        if (!roles.contains("ADMIN") && !roles.contains("QUALITY_MANAGER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Import réservé aux rôles ADMIN et QUALITY_MANAGER");
        }
    }
}
