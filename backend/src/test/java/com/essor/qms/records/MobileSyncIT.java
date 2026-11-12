package com.essor.qms.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.essor.qms.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Contrat M2 côté serveur : INCIDENT, idempotence, clientId/occurredAt, sync/changes, pièces jointes. */
@AutoConfigureMockMvc
class MobileSyncIT extends AbstractIntegrationTest {

    static final RequestPostProcessor TERRAIN = RecordsIT.user("sub-m-terrain", "m.terrain", "DEMO", "FIELD_USER");
    static final RequestPostProcessor QUALITE = RecordsIT.user("sub-m-qualite", "m.qualite", "DEMO",
            "QUALITY_MANAGER");
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private MvcResult perform(MockHttpServletRequestBuilder req, RequestPostProcessor who, String body, String key,
                              int expected) throws Exception {
        req.with(who).with(csrf());
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, RequestPostProcessor who, String body, String key,
                          int expected) throws Exception {
        String text = perform(req, who, body, key, expected).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private String siteId(RequestPostProcessor who) throws Exception {
        return call(get("/api/me"), who, null, null, 200).path("sites").get(0).path("id").asText();
    }

    private String incidentBody(String clientId) throws Exception {
        return """
                {"type":"INCIDENT","siteId":"%s","title":"Presqu'accident chariot","description":"Chute de palette",
                 "data":{"categorie":"presqu_accident","lieu":"Quai 3","blesses":false,"temoins":"A. B."},
                 "clientId":"%s","occurredAt":"2026-11-10T08:15:00Z"}""".formatted(siteId(TERRAIN), clientId);
    }

    private long count(String sql) throws Exception {
        try (var c = ownerConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void renvoiIdempotentSansDoublon() throws Exception {
        String clientId = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        MvcResult first = perform(post("/api/records"), TERRAIN, incidentBody(clientId), key, 201);
        MvcResult again = perform(post("/api/records"), TERRAIN, incidentBody(clientId), key, 201);
        assertThat(again.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");

        JsonNode inc = json.readTree(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(inc.path("reference").asText()).matches("INC-\\d{4}-\\d{4}");
        assertThat(inc.path("status").asText()).isEqualTo("DECLARE");
        assertThat(inc.path("clientId").asText()).isEqualTo(clientId);
        assertThat(inc.path("occurredAt").asText()).isEqualTo("2026-11-10T08:15:00Z");
        assertThat(inc.path("data").path("lieu").asText()).isEqualTo("Quai 3");
        assertThat(count("select count(*) from record where client_id = '" + clientId + "'")).isEqualTo(1);

        // clé perdue mais même clientId : toujours pas de doublon
        JsonNode third = call(post("/api/records"), TERRAIN, incidentBody(clientId), UUID.randomUUID().toString(),
                201);
        assertThat(third.path("id").asText()).isEqualTo(inc.path("id").asText());
        assertThat(count("select count(*) from record where client_id = '" + clientId + "'")).isEqualTo(1);

        // même clé pour une autre requête : refus
        JsonNode reused = call(post("/api/records/" + inc.path("id").asText() + "/transitions/analyser"), QUALITE,
                "{}", key, 409);
        assertThat(reused.path("error").asText()).isEqualTo("idempotency_key_reused");
        call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString()), "pas-un-uuid", 400);

        // transition rejouée : même réponse ; conflit (devenue impossible) : 409 rejoué tel quel
        String id = inc.path("id").asText();
        String tKey = UUID.randomUUID().toString();
        JsonNode classe = call(post("/api/records/" + id + "/transitions/classer"), QUALITE, "{}", tKey, 200);
        assertThat(classe.path("status").asText()).isEqualTo("CLASSE_SANS_SUITE");
        assertThat(call(post("/api/records/" + id + "/transitions/classer"), QUALITE, "{}", tKey, 200)
                .path("status").asText()).isEqualTo("CLASSE_SANS_SUITE");
        assertThat(count("select count(*) from audit_log where action = 'TRANSITION' and entity_id = '" + id + "'"))
                .isEqualTo(1);
        JsonNode conflict = call(post("/api/records/" + id + "/transitions/analyser"), QUALITE, "{}",
                UUID.randomUUID().toString(), 409);
        assertThat(conflict.path("error").asText()).isEqualTo("transition_impossible");

        call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString())
                .replace("2026-11-10T08:15:00Z", Instant.now().plusSeconds(3600).toString()), null, 400);
    }

    @Test
    void piecesJointesControleesEtTelechargeables() throws Exception {
        String id = call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString()), null, 201)
                .path("id").asText();
        String key = UUID.randomUUID().toString();
        MockMultipartFile photo = new MockMultipartFile("file", "photo 1.jpg", "image/jpeg", JPEG);
        JsonNode att = call(multipart("/api/records/" + id + "/attachments").file(photo), TERRAIN, null, key, 201);
        assertThat(att.path("fileName").asText()).isEqualTo("photo 1.jpg");
        assertThat(att.path("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(att.path("size").asLong()).isEqualTo(JPEG.length);
        assertThat(att.path("createdByName").asText()).isEqualTo("Nom m.terrain");
        assertThat(att.has("createdAt")).isTrue();
        // renvoi : même pièce jointe, pas de doublon
        JsonNode again = call(multipart("/api/records/" + id + "/attachments").file(photo), TERRAIN, null, key, 201);
        assertThat(again.path("id").asText()).isEqualTo(att.path("id").asText());
        call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "plan.png", "application/octet-stream", PNG)), TERRAIN, null,
                null, 201);

        // contenu ne correspondant pas au type : refus
        JsonNode fake = call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "x.png", "image/png", "<html>".getBytes())), TERRAIN, null, null,
                400);
        assertThat(fake.path("error").asText()).isEqualTo("invalid");
        call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", PNG)), TERRAIN, null, null, 400);
        call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "gros.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1])),
                TERRAIN, null, null, 413);

        JsonNode list = call(get("/api/records/" + id + "/attachments"), QUALITE, null, null, 200);
        assertThat(list).hasSize(2);
        MvcResult dl = perform(get("/api/attachments/" + att.path("id").asText()), QUALITE, null, null, 200);
        assertThat(dl.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
        assertThat(dl.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(dl.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(dl.getResponse().getHeader("Content-Disposition")).startsWith("inline").contains("photo");
        assertThat(perform(get("/api/attachments/" + att.path("id").asText()).param("download", "true"), QUALITE,
                null, null, 200).getResponse().getHeader("Content-Disposition")).startsWith("attachment");
    }

    @Test
    void piecesJointesIsoleesEntreTenants() throws Exception {
        try (var c = ownerConnection(); var st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('RLS-ATT', 'Autre') on conflict do nothing");
            st.execute("insert into site (tenant_id, code, name) select id, 'SIEGE', 'Siège' from tenant "
                    + "where code = 'RLS-ATT' on conflict do nothing");
        }
        RequestPostProcessor autre = RecordsIT.user("sub-m-autre", "m.autre", "RLS-ATT", "ADMIN", "QUALITY_MANAGER");
        String id = call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString()), null, 201)
                .path("id").asText();
        String attId = call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "p.png", "image/png", PNG)), TERRAIN, null, null, 201)
                .path("id").asText();

        call(get("/api/attachments/" + attId), autre, null, null, 404);
        call(get("/api/records/" + id + "/attachments"), autre, null, null, 404);
        call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "p.png", "image/png", PNG)), autre, null, null, 404);
        // même clé d'idempotence dans un autre tenant : indépendante
        String key = UUID.randomUUID().toString();
        call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString()), key, 201);
        String body = """
                {"type":"INCIDENT","siteId":"%s","title":"Autre tenant"}""".formatted(siteId(autre));
        assertThat(call(post("/api/records"), autre, body, key, 201).path("title").asText()).isEqualTo("Autre tenant");
    }

    @Test
    void syncChangesRendLesFichesDuDeclarantModifieesDepuis() throws Exception {
        JsonNode first = call(get("/api/sync/changes"), TERRAIN, null, null, 200);
        Instant since = Instant.parse(first.path("serverTime").asText());
        Thread.sleep(20);
        String id = call(post("/api/records"), TERRAIN, incidentBody(UUID.randomUUID().toString()), null, 201)
                .path("id").asText();
        call(post("/api/records"), QUALITE, """
                {"type":"NC","siteId":"%s","title":"NC d'un autre"}""".formatted(siteId(QUALITE)), null, 201);

        JsonNode changes = call(get("/api/sync/changes").param("since", since.toString()), TERRAIN, null, null, 200);
        assertThat(changes.path("records").findValuesAsText("id")).containsExactly(id);
        JsonNode summary = changes.path("records").get(0);
        assertThat(summary.path("type").asText()).isEqualTo("INCIDENT");
        assertThat(summary.path("statusLabel").asText()).isEqualTo("Déclaré");
        assertThat(summary.has("updatedAt")).isTrue();
        Instant serverTime = Instant.parse(changes.path("serverTime").asText());
        assertThat(serverTime).isAfter(since);

        assertThat(call(get("/api/sync/changes").param("since", serverTime.toString()), TERRAIN, null, null, 200)
                .path("records")).isEmpty();
        // une pièce jointe ajoutée fait remonter la fiche
        call(multipart("/api/records/" + id + "/attachments")
                .file(new MockMultipartFile("file", "p.png", "image/png", PNG)), QUALITE, null, null, 201);
        assertThat(call(get("/api/sync/changes").param("since", serverTime.toString()), TERRAIN, null, null, 200)
                .path("records").findValuesAsText("id")).containsExactly(id);
        call(get("/api/sync/changes").param("since", "hier"), TERRAIN, null, null, 400);
    }

    @Test
    void recordTypeDansMesTachesEtNotificationsEtClientIdNulSurLeWeb() throws Exception {
        String terrainId = null;
        call(get("/api/me"), TERRAIN, null, null, 200);
        for (JsonNode u : call(get("/api/users").param("q", "m.terrain"), QUALITE, null, null, 200)) {
            terrainId = u.path("id").asText();
        }
        JsonNode web = call(post("/api/records"), QUALITE, """
                {"type":"INCIDENT","siteId":"%s","title":"Déclaré sur le web"}""".formatted(siteId(QUALITE)), null,
                201);
        assertThat(web.get("clientId").isNull()).isTrue();
        assertThat(web.get("occurredAt").isNull()).isTrue();
        String id = web.path("id").asText();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/records/" + id), QUALITE,
                "{\"ownerId\":\"" + terrainId + "\"}", null, 200);

        JsonNode tasks = call(get("/api/my-tasks"), TERRAIN, null, null, 200);
        JsonNode mine = null;
        for (JsonNode t : tasks) {
            if (t.path("id").asText().equals(id)) {
                mine = t;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.path("recordType").asText()).isEqualTo("INCIDENT");
        JsonNode notifs = call(get("/api/notifications"), TERRAIN, null, null, 200).path("items");
        boolean found = false;
        for (JsonNode n : notifs) {
            if (n.path("recordId").asText().equals(id)) {
                found = true;
                assertThat(n.path("recordType").asText()).isEqualTo("INCIDENT");
            }
        }
        assertThat(found).isTrue();
        JsonNode wf = call(get("/api/record-types/INCIDENT/workflow"), TERRAIN, null, null, 200);
        for (JsonNode st : wf.path("states")) {
            boolean expectedFinal = st.path("code").asText().matches("CLOS|CLASSE_SANS_SUITE");
            assertThat(st.path("final").asBoolean()).as(st.path("code").asText()).isEqualTo(expectedFinal);
        }
    }
}
