package com.essor.qms.records;

import com.essor.qms.records.RecordService.StatusChanged;
import com.essor.qms.records.RecordStore.Row;
import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Audits (type de fiche AUDIT) : grille générée depuis les exigences, réponses, NC automatiques, programme. */
@Service
@Transactional
class AuditService {

    static final String AUDIT_TYPE = "AUDIT";
    static final String NC_TYPE = "NC";
    static final Set<String> RESULTS = Set.of("conforme", "ecart_mineur", "ecart_majeur", "observation", "na");
    static final Set<String> EDITABLE = Set.of("PLANIFIE", "EN_COURS");
    static final String REPORT_STATE = "RAPPORT";
    private static final Set<String> AUDIT_ROLES = Set.of("AUDITOR", "QUALITY_MANAGER", "ADMIN");
    private static final int MAX_QUESTIONS = 2000;

    record Question(UUID id, UUID requirementId, String requirementCode, String requirementTitle, int position,
                    String text, String result, String comment, String evidence, boolean closed,
                    Instant answeredAt, String answeredByName, UUID ncId, String ncReference) {

        /** Libellé de la question (alias de {@code text}, attendu par le mobile). */
        @com.fasterxml.jackson.annotation.JsonProperty("question")
        public String question() {
            return text;
        }
    }

    record NcRef(UUID id, String reference, String title, String status, String severity, UUID questionId) {
    }

    record ChapterRate(String chapter, String title, int answered, int conforming, int deviations,
                       Double conformityRate) {
    }

    record Summary(int questions, int answered, int conforme, int ecartMineur, int ecartMajeur, int observation,
                   int na, Double conformityRate, List<ChapterRate> chapters) {
    }

    record AuditDetail(UUID id, String reference, String title, String status, String statusLabel, UUID siteId,
                       String siteName, Map<String, Object> data, List<Question> questions, List<NcRef> ncs,
                       Summary summary) {
    }

    record ProgramItem(UUID id, String reference, String title, String status, String statusLabel, UUID siteId,
                       String siteName, String datePrevue, Object standardIds, Object auditeurs) {
    }

    record GenerateRequest(List<UUID> standardIds, List<UUID> requirementIds) {
    }

    record AnswerRequest(String result, String comment, String evidence, Boolean closed) {
    }

    record QuestionRequest(String text, UUID requirementId, Integer position) {
    }

    private record QRow(UUID id, UUID requirementId, String requirementCode, String requirementTitle, int position,
                        String text, String result, String comment, String evidence, boolean closed,
                        OffsetDateTime answeredAt, String answeredByName, UUID ncId, String ncReference,
                        String chapterCode, String chapterTitle) {
    }

    private static final String Q_SELECT = """
            select q.id, q.requirement_id, r.code as requirement_code, r.title as requirement_title, q.position,
                   q.text, q.result, q.comment, q.evidence, q.closed, q.answered_at,
                   coalesce(u.display_name, u.username) as answered_by_name, q.nc_id, nc.reference as nc_reference,
                   coalesce(top.code, r.code) as chapter_code, coalesce(top.title, r.title) as chapter_title
            from audit_question q
            left join requirement r on r.id = q.requirement_id
            left join app_user u on u.id = q.answered_by
            left join record nc on nc.id = q.nc_id
            left join lateral (
                with recursive up(id, code, title, parent_id, depth) as (
                    select r.id, r.code, r.title, r.parent_id, 0 where r.id is not null
                    union all
                    select p.id, p.code, p.title, p.parent_id, up.depth + 1
                    from requirement p join up on p.id = up.parent_id where up.depth < 20)
                select code, title from up where parent_id is null limit 1) top on true
            """;

    private final RecordStore store;
    private final RecordService records;
    private final StandardService standards;
    private final RecordTypes types;
    private final AuditLog audit;

    AuditService(RecordStore store, RecordService records, StandardService standards, RecordTypes types,
                 AuditLog audit) {
        this.store = store;
        this.records = records;
        this.standards = standards;
        this.types = types;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- lecture

    AuditDetail detail(UUID auditId) {
        Row a = loadAudit(auditId, false);
        List<QRow> rows = questionRows(auditId);
        return new AuditDetail(a.id(), a.reference(), a.title(), a.status(),
                types.get(AUDIT_TYPE).workflow().label(a.status()), a.siteId(), a.siteName(), store.data(a),
                rows.stream().map(AuditService::question).toList(), ncs(auditId), summary(rows));
    }

    List<Question> questions(UUID auditId) {
        loadAudit(auditId, false);
        return questionRows(auditId).stream().map(AuditService::question).toList();
    }

    List<ProgramItem> program(int year, List<String> statuses, boolean mine) {
        Workflow wf = types.get(AUDIT_TYPE).workflow();
        record P(UUID id, String reference, String title, String status, UUID siteId, String siteName,
                 String datePrevue, String standardIds, String auditeurs) {
        }
        return store.jdbc().sql("""
                select r.id, r.reference, r.title, r.status, r.site_id, s.name as site_name,
                       r.data->>'datePrevue' as date_prevue, (r.data->'standardIds')::text as standard_ids,
                       (r.data->'auditeurs')::text as auditeurs
                from record r left join site s on s.id = r.site_id
                where r.type_code = 'AUDIT'
                  and coalesce(substring(r.data->>'datePrevue' from '^(\\d{4})'),
                               extract(year from r.created_at)::text) = :y
                  and (cardinality(cast(:st as text[])) = 0 or r.status = any(cast(:st as text[])))
                  and (not :mine or r.owner_id = app_current_user()
                       or jsonb_exists(coalesce(r.data->'auditeurs', '[]'::jsonb), cast(app_current_user() as text)))
                order by r.data->>'datePrevue' nulls last, r.reference
                """).param("y", String.valueOf(year)).param("st", statuses.toArray(String[]::new))
                .param("mine", mine).query(P.class).list().stream()
                .map(p -> new ProgramItem(p.id(), p.reference(), p.title(), p.status(), wf.label(p.status()),
                        p.siteId(), p.siteName(), p.datePrevue(), parseJson(p.standardIds()), parseJson(p.auditeurs())))
                .toList();
    }

    List<NcRef> ncs(UUID auditId) {
        return store.jdbc().sql("""
                select nc.id, nc.reference, nc.title, nc.status, nc.severity, q.id as question_id
                from audit_question q join record nc on nc.id = q.nc_id
                where q.audit_id = :a order by q.position
                """).param("a", auditId).query(NcRef.class).list();
    }

    // ---------------------------------------------------------------- grille

    /** Ajoute une question par exigence auditable des référentiels (ou des exigences) choisis ; sans doublon. */
    List<Question> generate(UUID auditId, GenerateRequest body) {
        requireAuditor();
        Row a = loadAudit(auditId, true);
        requireEditable(a);
        List<UUID> standardIds = body == null || body.standardIds() == null ? List.of() : body.standardIds();
        List<UUID> requirementIds = body == null || body.requirementIds() == null ? List.of() : body.requirementIds();
        if (standardIds.isEmpty() && requirementIds.isEmpty()) {
            throw ApiException.badRequest("standardIds ou requirementIds obligatoire");
        }
        standardIds.forEach(standards::standard);
        record Req(UUID id, String code, String title) {
        }
        String filter = requirementIds.isEmpty() ? "r.standard_id = any(:s)" : "r.id = any(:r)";
        List<Req> reqs = store.jdbc().sql("""
                select r.id, r.code, r.title from requirement r join standard s on s.id = r.standard_id
                where r.auditable and %s order by s.code, s.version, r.position
                """.formatted(filter))
                .param("s", standardIds.toArray(UUID[]::new)).param("r", requirementIds.toArray(UUID[]::new))
                .query(Req.class).list();
        if (!requirementIds.isEmpty() && reqs.isEmpty()) {
            throw ApiException.badRequest("Aucune exigence auditable parmi requirementIds");
        }
        int next = nextPosition(auditId);
        int added = 0;
        for (Req r : reqs) {
            if (next >= MAX_QUESTIONS) {
                throw ApiException.badRequest("Grille limitée à " + MAX_QUESTIONS + " questions");
            }
            added += store.jdbc().sql("""
                    insert into audit_question (tenant_id, audit_id, requirement_id, position, text)
                    values (app_current_tenant(), :a, :r, :p, :t)
                    on conflict (tenant_id, audit_id, requirement_id) where requirement_id is not null do nothing
                    """).param("a", auditId).param("r", r.id()).param("p", next)
                    .param("t", cut(r.code() + " — " + r.title(), 1000)).update();
            next++;
        }
        Map<String, Object> data = store.data(a);
        Set<Object> sids = new LinkedHashSet<>(data.get("standardIds") instanceof List<?> l ? l : List.of());
        standardIds.forEach(s -> sids.add(s.toString()));
        data.put("standardIds", List.copyOf(sids));
        store.jdbc().sql("update record set data = cast(:d as jsonb), updated_at = now() where id = :id")
                .param("d", store.write(data)).param("id", auditId).update();
        audit.record("AUDIT_CHECKLIST_GENERATED", "record", auditId.toString(),
                store.write(Map.of("added", added)));
        return questions(auditId);
    }

    Question addQuestion(UUID auditId, QuestionRequest body) {
        requireAuditor();
        requireEditable(loadAudit(auditId, true));
        String text = requireText(body == null ? null : body.text());
        if (body.requirementId() != null) {
            standards.refs(List.of(body.requirementId())).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Exigence inconnue dans ce tenant"));
        }
        UUID id = UUID.randomUUID();
        try {
            store.jdbc().sql("""
                    insert into audit_question (id, tenant_id, audit_id, requirement_id, position, text)
                    values (:id, app_current_tenant(), :a, :r, :p, :t)
                    """).param("id", id).param("a", auditId).param("r", body.requirementId())
                    .param("p", body.position() == null ? nextPosition(auditId) : body.position())
                    .param("t", text).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid", "Exigence déjà présente dans la grille");
        }
        touch(auditId);
        return question(auditId, id);
    }

    Question updateQuestion(UUID auditId, UUID questionId, QuestionRequest body) {
        requireAuditor();
        requireEditable(loadAudit(auditId, true));
        Question q = question(auditId, questionId);
        String text = body == null || body.text() == null ? q.text() : requireText(body.text());
        int position = body == null || body.position() == null ? q.position() : body.position();
        store.jdbc().sql("update audit_question set text = :t, position = :p where id = :id")
                .param("t", text).param("p", position).param("id", questionId).update();
        touch(auditId);
        return question(auditId, questionId);
    }

    void deleteQuestion(UUID auditId, UUID questionId) {
        requireAuditor();
        requireEditable(loadAudit(auditId, true));
        Question q = question(auditId, questionId);
        if (q.result() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked", "Question déjà répondue : suppression impossible");
        }
        store.jdbc().sql("delete from audit_question where id = :id").param("id", questionId).update();
        touch(auditId);
    }

    // ---------------------------------------------------------------- réponses

    /** Réponse à une question ; {@code closed=true} sur un écart crée aussitôt la NC liée. */
    Question answer(UUID auditId, UUID questionId, AnswerRequest body) {
        requireAuditor();
        Row a = loadAudit(auditId, true);
        requireEditable(a);
        Question q = question(auditId, questionId);
        if (body == null || body.result() == null || !RESULTS.contains(body.result())) {
            throw ApiException.badRequest("result : conforme, ecart_mineur, ecart_majeur, observation ou na");
        }
        if (q.ncId() != null && !isDeviation(body.result())) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked",
                    "Une NC a déjà été créée pour cet écart : le résultat doit rester un écart");
        }
        String comment = limit(body.comment(), 10000, "comment");
        String evidence = limit(body.evidence(), 10000, "evidence");
        boolean closed = Boolean.TRUE.equals(body.closed()) || q.closed();
        store.jdbc().sql("""
                update audit_question set result = :r, comment = :c, evidence = :e, closed = :cl,
                       answered_at = now(), answered_by = app_current_user()
                where id = :id
                """).param("r", body.result()).param("c", comment).param("e", evidence).param("cl", closed)
                .param("id", questionId).update();
        audit.record("AUDIT_ANSWERED", "record", auditId.toString(),
                store.write(Map.of("questionId", questionId, "result", body.result())));
        if (closed && isDeviation(body.result()) && q.ncId() == null) {
            createNc(a, question(auditId, questionId));
        }
        touch(auditId);
        return question(auditId, questionId);
    }

    /** Passage au rapport : une NC par écart qui n'en a pas encore. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onStatusChanged(StatusChanged e) {
        if (!AUDIT_TYPE.equals(e.typeCode()) || !REPORT_STATE.equals(e.to())) {
            return;
        }
        Row a = loadAudit(e.recordId(), false);
        for (QRow q : questionRows(e.recordId())) {
            if (isDeviation(q.result()) && q.ncId() == null) {
                createNc(a, question(q));
            }
        }
    }

    private void createNc(Row auditRow, Question q) {
        boolean major = "ecart_majeur".equals(q.result());
        String req = q.requirementCode() == null ? q.text() : q.requirementCode() + " " + q.requirementTitle();
        String title = (major ? "Écart majeur" : "Écart mineur") + " audit " + auditRow.reference() + " — " + req;
        StringBuilder description = new StringBuilder("Constat d'audit ").append(auditRow.reference()).append(" : ")
                .append(q.text());
        if (q.comment() != null) {
            description.append("\n").append(q.comment());
        }
        if (q.evidence() != null) {
            description.append("\nPreuve : ").append(q.evidence());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auditId", auditRow.id().toString());
        data.put("auditReference", auditRow.reference());
        data.put("questionId", q.id().toString());
        if (q.requirementId() != null) {
            data.put("requirementId", q.requirementId().toString());
        }
        UUID nc = records.createInternal(NC_TYPE, title, cut(description.toString(), 10000),
                major ? "majeure" : "mineure", auditRow.siteId(), null, data,
                Map.of("auditId", auditRow.id(), "questionId", q.id()));
        if (q.requirementId() != null) {
            standards.link(nc, q.requirementId());
        }
        store.jdbc().sql("update audit_question set nc_id = :nc, closed = true where id = :id")
                .param("nc", nc).param("id", q.id()).update();
        audit.record("AUDIT_NC_CREATED", "record", auditRow.id().toString(),
                store.write(Map.of("questionId", q.id(), "ncId", nc)));
    }

    // ---------------------------------------------------------------- synthèse

    static Summary summary(List<QRow> rows) {
        int[] counts = new int[5];
        Map<String, int[]> chapters = new LinkedHashMap<>();
        Map<String, String> chapterTitles = new LinkedHashMap<>();
        for (QRow q : rows) {
            String key = q.chapterCode() == null ? "—" : q.chapterCode();
            chapterTitles.putIfAbsent(key, q.chapterCode() == null ? "Hors référentiel" : q.chapterTitle());
            int[] c = chapters.computeIfAbsent(key, k -> new int[3]);
            if (q.result() == null) {
                continue;
            }
            switch (q.result()) {
                case "conforme" -> counts[0]++;
                case "ecart_mineur" -> counts[1]++;
                case "ecart_majeur" -> counts[2]++;
                case "observation" -> counts[3]++;
                default -> counts[4]++;
            }
            if (!"na".equals(q.result())) {
                c[0]++;
                if (isDeviation(q.result())) {
                    c[2]++;
                } else {
                    c[1]++;
                }
            }
        }
        List<ChapterRate> rates = new ArrayList<>();
        chapters.forEach((k, c) -> rates.add(new ChapterRate(k, chapterTitles.get(k), c[0], c[1], c[2],
                c[0] == 0 ? null : round(100.0 * c[1] / c[0]))));
        int assessed = counts[0] + counts[1] + counts[2] + counts[3];
        int answered = assessed + counts[4];
        return new Summary(rows.size(), answered, counts[0], counts[1], counts[2], counts[3], counts[4],
                assessed == 0 ? null : round(100.0 * (counts[0] + counts[3]) / assessed), rates);
    }

    List<QRow> questionRows(UUID auditId) {
        return store.jdbc().sql(Q_SELECT + " where q.audit_id = :a order by q.position, q.id")
                .param("a", auditId).query(QRow.class).list();
    }

    // ---------------------------------------------------------------- interne

    Row loadAudit(UUID id, boolean forUpdate) {
        Row r = records.load(id, forUpdate);
        if (!AUDIT_TYPE.equals(r.typeCode())) {
            throw ApiException.notFound("Audit");
        }
        return r;
    }

    private Question question(UUID auditId, UUID questionId) {
        return store.jdbc().sql(Q_SELECT + " where q.audit_id = :a and q.id = :id")
                .param("a", auditId).param("id", questionId).query(QRow.class).optional()
                .map(AuditService::question).orElseThrow(() -> ApiException.notFound("Question"));
    }

    private static Question question(QRow r) {
        return new Question(r.id(), r.requirementId(), r.requirementCode(), r.requirementTitle(), r.position(),
                r.text(), r.result(), r.comment(), r.evidence(), r.closed(),
                r.answeredAt() == null ? null : r.answeredAt().toInstant(), r.answeredByName(), r.ncId(),
                r.ncReference());
    }

    private int nextPosition(UUID auditId) {
        return store.jdbc().sql("select coalesce(max(position) + 1, 0) from audit_question where audit_id = :a")
                .param("a", auditId).query(Integer.class).single();
    }

    private void touch(UUID auditId) {
        store.jdbc().sql("update record set updated_at = now() where id = :id").param("id", auditId).update();
    }

    private void requireEditable(Row a) {
        if (!EDITABLE.contains(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "record_locked",
                    "Audit au statut « " + types.get(AUDIT_TYPE).workflow().label(a.status())
                            + " » : grille et réponses non modifiables");
        }
    }

    private void requireAuditor() {
        if (store.currentRoles().stream().noneMatch(AUDIT_ROLES::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Réservé aux rôles AUDITOR, QUALITY_MANAGER et ADMIN");
        }
    }

    static boolean isDeviation(String result) {
        return "ecart_mineur".equals(result) || "ecart_majeur".equals(result);
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank() || text.length() > 1000) {
            throw ApiException.badRequest("text obligatoire (1000 caractères max)");
        }
        return text.strip();
    }

    private static String limit(String v, int max, String name) {
        if (v != null && v.length() > max) {
            throw ApiException.badRequest(name + " trop long (" + max + " caractères max)");
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static Double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private Object parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
