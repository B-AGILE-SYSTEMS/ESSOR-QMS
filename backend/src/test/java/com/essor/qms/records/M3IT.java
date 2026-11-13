package com.essor.qms.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.essor.qms.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Contrat M3 côté serveur : référentiels et import, audits jusqu'au PDF, NC d'écart, documents, isolation. */
@AutoConfigureMockMvc
class M3IT extends AbstractIntegrationTest {

    static final RequestPostProcessor QUALITE = RecordsIT.user("sub-3-qualite", "t3.qualite", "DEMO", "QUALITY_MANAGER");
    static final RequestPostProcessor AUDITEUR = RecordsIT.user("sub-3-auditeur", "t3.auditeur", "DEMO", "AUDITOR");
    static final RequestPostProcessor ADMIN = RecordsIT.user("sub-3-admin", "t3.admin", "DEMO", "ADMIN");
    static final RequestPostProcessor TERRAIN = RecordsIT.user("sub-3-terrain", "t3.terrain", "DEMO", "FIELD_USER");
    static final RequestPostProcessor AUTRE_TERRAIN =
            RecordsIT.user("sub-3-terrain2", "t3.terrain2", "DEMO", "FIELD_USER");
    static final RequestPostProcessor ADMIN_LECTEUR = RecordsIT.user("sub-3-lecteur", "t3.lecteur", "DEMO", "READER");
    static final byte[] PDF = "%PDF-1.4\n% test\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    DeadlineJob deadlines;

    private MvcResult perform(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body, int expected)
            throws Exception {
        req.with(who).with(csrf());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body, int expected)
            throws Exception {
        String text = perform(req, who, body, expected).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private String siteId(RequestPostProcessor who) throws Exception {
        return call(get("/api/me"), who, null, 200).path("sites").get(0).path("id").asText();
    }

    private String userId(RequestPostProcessor who) throws Exception {
        String username = call(get("/api/me"), who, null, 200).path("username").asText();
        for (JsonNode u : call(get("/api/users").param("q", username), who, null, 200)) {
            if (u.path("username").asText().equals(username)) {
                return u.path("id").asText();
            }
        }
        throw new AssertionError("utilisateur absent");
    }

    private JsonNode importFile(MockMultipartFile file, String code, boolean dryRun, RequestPostProcessor who,
                                int expected) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/standards/import").file(file);
        req.param("standardCode", code).param("standardVersion", "2018").param("standardTitle", "Norme " + code)
                .param("dryRun", String.valueOf(dryRun));
        return call(req, who, null, expected);
    }

    static byte[] xlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static final String[][] NORME = {
            {"code", "titre", "texte", "parent", "auditable"},
            {"8", "Activités opérationnelles", "Chapitre", "", "non"},
            {"8.1", "Maîtrise opérationnelle", "Résumé maison.", "8", "oui"},
            {"8.1.2", "Réduction des risques", "Résumé maison.", "8.1", "oui"},
            {"8.2", "Situations d'urgence", "Résumé maison.", "8", "oui"},
            {"9", "Évaluation", "Chapitre", "", "non"},
            {"9.2", "Audit interne", "Résumé maison.", "9", "oui"}};

    /** Importe la norme de test dans un référentiel de code unique ; rend son id. */
    private String importNorme(String code) throws Exception {
        JsonNode r = importFile(new MockMultipartFile("file", "norme.xlsx", StandardImport.XLSX, xlsx(NORME)), code,
                false, QUALITE, 200);
        assertThat(r.path("applied").asBoolean()).isTrue();
        assertThat(r.path("created").asInt()).isEqualTo(6);
        return r.path("standardId").asText();
    }

    private List<JsonNode> flatten(JsonNode nodes) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode n : nodes) {
            all.add(n);
            all.addAll(flatten(n.path("children")));
        }
        return all;
    }

    private String requirementId(String standardId, String code) throws Exception {
        return flatten(call(get("/api/standards/" + standardId + "/requirements"), QUALITE, null, 200)).stream()
                .filter(n -> n.path("code").asText().equals(code)).findFirst().orElseThrow().path("id").asText();
    }

    private long count(String sql) throws Exception {
        try (var c = ownerConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------- import

    @Test
    void importAvecErreursLigneParLigneSimulationPuisImportReel() throws Exception {
        String csv = """
                code;titre;texte;parent;auditable
                4;Contexte;;;non
                4.1;;Sans titre;4;oui
                4.2;Parties intéressées;"Texte ; avec point-virgule";4;peut-être
                4.3;Domaine;;9.9;oui
                4.1;Doublon;;4;oui
                """;
        JsonNode r = importFile(new MockMultipartFile("file", "n.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
                "ERR", false, QUALITE, 200);
        assertThat(r.path("applied").asBoolean()).isFalse();
        List<String> errors = new ArrayList<>();
        r.path("errors").forEach(e -> errors.add(e.path("line").asInt() + ":" + e.path("message").asText()));
        assertThat(errors).anyMatch(e -> e.startsWith("3:") && e.contains("titre"))
                .anyMatch(e -> e.startsWith("4:") && e.contains("auditable"))
                .anyMatch(e -> e.startsWith("5:") && e.contains("parent 9.9 inconnu"))
                .anyMatch(e -> e.startsWith("6:") && e.contains("en double"));
        assertThat(count("select count(*) from standard where code = 'ERR'")).isZero();

        // simulation d'un fichier valide : rapport sans écriture
        JsonNode dry = importFile(new MockMultipartFile("file", "n.xlsx", StandardImport.XLSX, xlsx(NORME)), "SIM",
                true, QUALITE, 200);
        assertThat(dry.path("errors").size()).isZero();
        assertThat(dry.path("created").asInt()).isEqualTo(6);
        assertThat(dry.path("applied").asBoolean()).isFalse();
        assertThat(count("select count(*) from standard where code = 'SIM'")).isZero();

        // import réel puis réimport : mises à jour, arbre construit
        String id = importNorme("IMP");
        JsonNode again = importFile(new MockMultipartFile("file", "n.xlsx", StandardImport.XLSX, xlsx(NORME)), "IMP",
                false, QUALITE, 200);
        assertThat(again.path("updated").asInt()).isEqualTo(6);
        assertThat(again.path("created").asInt()).isZero();
        JsonNode tree = call(get("/api/standards/" + id + "/requirements"), TERRAIN, null, 200);
        assertThat(tree.size()).isEqualTo(2);
        assertThat(tree.get(0).path("children").get(0).path("children").get(0).path("code").asText()).isEqualTo("8.1.2");
        JsonNode list = call(get("/api/standards"), TERRAIN, null, 200);
        assertThat(list.findValuesAsText("code")).contains("IMP");

        // droits et modèle
        importFile(new MockMultipartFile("file", "n.xlsx", StandardImport.XLSX, xlsx(NORME)), "X", false, TERRAIN, 403);
        byte[] template = perform(get("/api/standards/import/template"), TERRAIN, null, 200).getResponse()
                .getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("code");
        }
        assertThat(StandardImport.parse(template, "m.xlsx")).hasSize(2);
    }

    // ---------------------------------------------------------------- audit

    @Test
    void parcoursAuditCompletJusquAuPdfAvecNcAutomatiques() throws Exception {
        String std = importNorme("AUD45001");
        String auditorId = userId(AUDITEUR);
        JsonNode audit = call(post("/api/records"), QUALITE, """
                {"type":"AUDIT","siteId":"%s","title":"Audit interne Entrepôt Nord",
                 "data":{"perimetre":"Stockage","auditeurs":["%s"],"audites":"Chef d'entrepôt",
                         "datePrevue":"%d-06-15"}}""".formatted(siteId(QUALITE), auditorId, LocalDate.now().getYear()),
                201);
        String id = audit.path("id").asText();
        assertThat(audit.path("status").asText()).isEqualTo("PLANIFIE");

        JsonNode questions = call(post("/api/audits/" + id + "/checklist/generate"), AUDITEUR,
                "{\"standardIds\":[\"" + std + "\"]}", 200);
        assertThat(questions.size()).isEqualTo(4); // exigences auditables seulement
        assertThat(call(post("/api/audits/" + id + "/checklist/generate"), AUDITEUR,
                "{\"standardIds\":[\"" + std + "\"]}", 200).size()).isEqualTo(4); // sans doublon
        JsonNode checklist = call(get("/api/audits/" + id + "/checklist"), AUDITEUR, null, 200);
        assertThat(checklist.get(0).path("question").asText()).startsWith("8.1");
        assertThat(checklist.get(0).path("requirementCode").asText()).isEqualTo("8.1");
        call(post("/api/audits/" + id + "/questions"), AUDITEUR, "{\"text\":\"Question libre : affichage\"}", 201);

        // programme annuel filtré
        JsonNode program = call(get("/api/audits").param("year", String.valueOf(LocalDate.now().getYear()))
                .param("status", "PLANIFIE,EN_COURS").param("auditor", "me"), AUDITEUR, null, 200);
        assertThat(program.findValuesAsText("id")).contains(id);
        assertThat(call(get("/api/audits").param("year", "2001"), AUDITEUR, null, 200).size()).isZero();

        call(post("/api/records/" + id + "/transitions/demarrer"), AUDITEUR, "{}", 200);
        String q1 = checklist.get(0).path("id").asText();
        String q2 = checklist.get(1).path("id").asText();
        String q3 = checklist.get(2).path("id").asText();
        String q4 = checklist.get(3).path("id").asText();
        // réponse mobile idempotente
        String key = UUID.randomUUID().toString();
        String body = "{\"result\":\"conforme\",\"comment\":\"RAS\",\"evidence\":\"Registre vu\"}";
        call(put("/api/audits/" + id + "/answers/" + q1).header("Idempotency-Key", key), AUDITEUR, body, 200);
        MvcResult replay = perform(put("/api/audits/" + id + "/answers/" + q1).header("Idempotency-Key", key),
                AUDITEUR, body, 200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        call(put("/api/audits/" + id + "/answers/" + q1), AUDITEUR, "{\"result\":\"bof\"}", 400);

        // écart majeur clos : NC immédiate, liée à l'exigence, au site et à l'audit
        JsonNode major = call(put("/api/audits/" + id + "/answers/" + q2), AUDITEUR,
                "{\"result\":\"ecart_majeur\",\"comment\":\"Allée encombrée\",\"closed\":true}", 200);
        String ncId = major.path("ncId").asText();
        assertThat(ncId).isNotBlank();
        JsonNode nc = call(get("/api/records/" + ncId), QUALITE, null, 200);
        assertThat(nc.path("type").asText()).isEqualTo("NC");
        assertThat(nc.path("severity").asText()).isEqualTo("majeure");
        assertThat(nc.path("siteId").asText()).isEqualTo(audit.path("siteId").asText());
        assertThat(nc.path("data").path("auditId").asText()).isEqualTo(id);
        JsonNode links = call(get("/api/records/" + ncId + "/requirements"), QUALITE, null, 200);
        assertThat(links.get(0).path("code").asText()).isEqualTo("8.1.2");

        // écart mineur non clos : NC créée au passage au rapport
        call(put("/api/audits/" + id + "/answers/" + q3), AUDITEUR,
                "{\"result\":\"ecart_mineur\",\"comment\":\"Plan d'urgence non affiché\"}", 200);
        call(put("/api/audits/" + id + "/answers/" + q4), AUDITEUR, "{\"result\":\"observation\"}", 200);
        call(post("/api/records/" + id + "/transitions/rediger_rapport"), AUDITEUR, "{}", 200);
        JsonNode detail = call(get("/api/audits/" + id), QUALITE, null, 200);
        assertThat(detail.path("ncs").size()).isEqualTo(2);
        assertThat(detail.path("summary").path("conformityRate").asDouble()).isEqualTo(50.0);
        call(put("/api/audits/" + id + "/answers/" + q4), AUDITEUR, "{\"result\":\"conforme\"}", 409);

        // exigence : constats et NC liées
        String req = requirementId(std, "8.1.2");
        JsonNode reqDetail = call(get("/api/requirements/" + req), QUALITE, null, 200);
        assertThat(reqDetail.path("findings").get(0).path("result").asText()).isEqualTo("ecart_majeur");
        assertThat(reqDetail.path("records").findValuesAsText("id")).contains(ncId);

        // rapport PDF : s'ouvre et contient la synthèse
        MvcResult pdf = perform(get("/api/audits/" + id + "/report.pdf"), QUALITE, null, 200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        PdfReader reader = new PdfReader(pdf.getResponse().getContentAsByteArray());
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            text.append(extractor.getTextFromPage(p));
        }
        reader.close();
        assertThat(text.toString()).contains("Synthèse", "Taux de conformité", "50.0 %",
                audit.path("reference").asText(), nc.path("reference").asText());

        call(post("/api/records/" + id + "/transitions/cloturer"), QUALITE, "{}", 200);
    }

    @Test
    void auditeurDesigneSansRoleAuditorExecuteLAuditApresDemarrage() throws Exception {
        String std = importNorme("DES45001");
        String terrainId = userId(TERRAIN);
        JsonNode audit = call(post("/api/records"), QUALITE, """
                {"type":"AUDIT","siteId":"%s","title":"Audit désigné",
                 "data":{"auditeurs":["%s"],"datePrevue":"%d-03-10"}}""".formatted(siteId(QUALITE), terrainId,
                LocalDate.now().getYear()), 201);
        String id = audit.path("id").asText();
        JsonNode questions = call(post("/api/audits/" + id + "/checklist/generate"), QUALITE,
                "{\"standardIds\":[\"" + std + "\"]}", 200);
        String q1 = questions.get(0).path("id").asText();

        // [3] liste mobile : l'auditeur désigné voit l'audit
        JsonNode mine = call(get("/api/audits").param("auditor", "me").param("status", "PLANIFIE,EN_COURS"),
                TERRAIN, null, 200);
        assertThat(mine.findValuesAsText("id")).contains(id);
        // [4] réponse refusée tant que l'audit n'est pas démarré
        JsonNode early = call(put("/api/audits/" + id + "/answers/" + q1), TERRAIN, "{\"result\":\"conforme\"}", 409);
        assertThat(early.toString()).contains("démarré");
        // [1] un utilisateur ni AUDITOR ni désigné reste refusé
        call(post("/api/records/" + id + "/transitions/demarrer"), ADMIN_LECTEUR, "{}", 403);
        // [1] transitions et réponses autorisées à l'auditeur désigné (rôle FIELD_USER seulement)
        JsonNode started = call(post("/api/records/" + id + "/transitions/demarrer"), TERRAIN, "{}", 200);
        assertThat(started.path("status").asText()).isEqualTo("EN_COURS");
        call(put("/api/audits/" + id + "/answers/" + q1), TERRAIN, "{\"result\":\"conforme\"}", 200);
        call(put("/api/audits/" + id + "/answers/" + q1), AUTRE_TERRAIN, "{\"result\":\"conforme\"}", 403);
        // rapport possible avec des questions sans réponse : comptées « non évaluées »
        call(post("/api/records/" + id + "/transitions/rediger_rapport"), TERRAIN, "{}", 200);
        JsonNode detail = call(get("/api/audits/" + id), QUALITE, null, 200);
        assertThat(detail.path("summary").path("notAssessed").asInt()).isEqualTo(questions.size() - 1);
        assertThat(detail.path("summary").path("conformityRate").asDouble()).isEqualTo(100.0);

        // [6] PDF : nom de l'auditeur (pas son UUID), fichier rapport-<référence>.pdf
        MvcResult pdf = perform(get("/api/audits/" + id + "/report.pdf"), QUALITE, null, 200);
        assertThat(pdf.getResponse().getHeader("Content-Disposition"))
                .contains("rapport-" + audit.path("reference").asText() + ".pdf");
        PdfReader reader = new PdfReader(pdf.getResponse().getContentAsByteArray());
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            text.append(extractor.getTextFromPage(p));
        }
        reader.close();
        assertThat(text.toString()).contains("Nom t3.terrain", "Non évaluée").doesNotContain(terrainId);
    }

    // ---------------------------------------------------------------- documents

    @Test
    void cycleDocumentaireUneSeuleVersionEnVigueurEtAccusesDeLecture() throws Exception {
        userId(TERRAIN); // provisionnement du destinataire
        String code = "PR-QHSE-" + (System.nanoTime() % 100000);
        JsonNode doc = call(post("/api/documents"), QUALITE, """
                {"code":"%s","title":"Circulation des chariots","docType":"procedure","processus":"Logistique",
                 "recipients":{"roles":["FIELD_USER"]},"verifierRoles":["QUALITY_MANAGER"],
                 "approverRoles":["ADMIN"],"reviewDate":"%s"}""".formatted(code, LocalDate.now().plusDays(20)), 201);
        String id = doc.path("document").path("id").asText();
        call(post("/api/records"), QUALITE, "{\"type\":\"DOCUMENT\",\"siteId\":\"" + siteId(QUALITE)
                + "\",\"title\":\"x\"}", 400);

        for (int v = 1; v <= 2; v++) {
            JsonNode version = call(multipart("/api/documents/" + id + "/versions")
                    .file(new MockMultipartFile("file", "proc-v" + v + ".pdf", "application/pdf", PDF))
                    .param("note", "Version " + v), QUALITE, null, 201);
            assertThat(version.path("number").asInt()).isEqualTo(v);
            assertThat(version.path("status").asText()).isEqualTo("REDACTION");
            call(multipart("/api/documents/" + id + "/versions")
                    .file(new MockMultipartFile("file", "bis.pdf", "application/pdf", PDF)), QUALITE, null, 409);
            String base = "/api/documents/" + id + "/versions/" + v + "/transitions/";
            call(post(base + "soumettre"), QUALITE, "{}", 200);
            call(post(base + "verifier"), TERRAIN, "{}", 403);
            call(post(base + "verifier"), QUALITE, "{}", 200);
            call(post(base + "approuver"), QUALITE, "{}", 403);
            JsonNode diffused = call(post(base + "approuver"), ADMIN, "{}", 200);
            assertThat(diffused.path("inForce").asBoolean()).isTrue();
        }
        JsonNode view = call(get("/api/documents/" + id), TERRAIN, null, 200);
        assertThat(view.path("versions").findValuesAsText("status")).containsExactly("DIFFUSE", "PERIME");
        assertThat(count("select count(*) from document_version where document_id = '" + id
                + "' and status = 'DIFFUSE'")).isEqualTo(1);
        JsonNode list = call(get("/api/documents").param("type", "procedure").param("q", code), TERRAIN, null, 200);
        assertThat(list.get(0).path("currentVersion").path("number").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("status").asText()).isEqualTo("DIFFUSE");
        MvcResult file = perform(get("/api/documents/" + id + "/versions/1/file"), TERRAIN, null, 200);
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        call(post("/api/records/" + id + "/transitions/soumettre"), QUALITE, "{}", 409);

        // notification de diffusion et accusés de lecture
        JsonNode notifs = call(get("/api/notifications"), TERRAIN, null, 200);
        assertThat(notifs.path("items").findValuesAsText("type")).contains("DOCUMENT_DIFFUSED");
        String terrainId = userId(TERRAIN);
        JsonNode before = call(get("/api/documents/" + id + "/versions/2/acks"), QUALITE, null, 200);
        assertThat(before.path("pending").findValuesAsText("userId")).contains(terrainId);
        call(post("/api/documents/" + id + "/versions/1/ack"), TERRAIN, null, 409); // périmée
        call(post("/api/documents/" + id + "/versions/2/ack"), QUALITE, null, 403); // non destinataire
        JsonNode acks = call(post("/api/documents/" + id + "/versions/2/ack"), TERRAIN, null, 200);
        assertThat(acks.path("acknowledged").findValuesAsText("userId")).contains(terrainId);
        assertThat(acks.path("pending").findValuesAsText("userId")).doesNotContain(terrainId);

        // alerte de revue J-30 (une seule notification)
        deadlines.run(LocalDate.now());
        deadlines.run(LocalDate.now());
        assertThat(count("select count(*) from notification where type = 'REVIEW_DUE' and record_id = '" + id + "'"))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void referentielsAuditsEtDocumentsInvisiblesDUnAutreTenant() throws Exception {
        String std = importNorme("ISO-ISOL");
        String req = requirementId(std, "8.1");
        JsonNode audit = call(post("/api/records"), QUALITE, "{\"type\":\"AUDIT\",\"siteId\":\"" + siteId(QUALITE)
                + "\",\"title\":\"Audit isolé\"}", 201);
        JsonNode doc = call(post("/api/documents"), QUALITE,
                "{\"code\":\"ISOL-1\",\"title\":\"Doc isolé\",\"docType\":\"instruction\"}", 201);
        try (var c = ownerConnection(); var st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('RLS-M3', 'Autre') on conflict do nothing");
        }
        RequestPostProcessor autre = RecordsIT.user("sub-3-autre", "t3.autre", "RLS-M3", "ADMIN", "QUALITY_MANAGER");
        assertThat(call(get("/api/standards"), autre, null, 200).size()).isZero();
        call(get("/api/standards/" + std + "/requirements"), autre, null, 404);
        call(get("/api/requirements/" + req), autre, null, 404);
        call(get("/api/audits/" + audit.path("id").asText()), autre, null, 404);
        call(get("/api/audits/" + audit.path("id").asText() + "/report.pdf"), autre, null, 404);
        call(get("/api/documents/" + doc.path("document").path("id").asText()), autre, null, 404);
        assertThat(call(get("/api/documents"), autre, null, 200).size()).isZero();
        assertThat(call(get("/api/audits").param("year", String.valueOf(LocalDate.now().getYear())), autre, null, 200)
                .size()).isZero();
        // RLS forcée sur toutes les nouvelles tables
        assertThat(count("""
                select count(*) from pg_class where relname in ('standard','requirement','record_requirement',
                  'audit_question','document_version','document_ack') and relrowsecurity and relforcerowsecurity
                """)).isEqualTo(6);
    }
}
