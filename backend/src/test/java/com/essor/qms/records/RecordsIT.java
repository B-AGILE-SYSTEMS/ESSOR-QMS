package com.essor.qms.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.essor.qms.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Parcours NC complet par l'API, refus de transitions, isolation RLS, notifications, SSE, tâche planifiée. */
@AutoConfigureMockMvc
class RecordsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    DeadlineJob deadlines;

    static final RequestPostProcessor QUALITE = user("sub-r-qualite", "r.qualite", "DEMO", "QUALITY_MANAGER");
    static final RequestPostProcessor TERRAIN = user("sub-r-terrain", "r.terrain", "DEMO", "FIELD_USER");
    static final RequestPostProcessor ADMIN = user("sub-r-admin", "r.admin", "DEMO", "ADMIN");
    static final RequestPostProcessor LECTEUR = user("sub-r-lecteur", "r.lecteur", "DEMO", "READER");

    static RequestPostProcessor user(String sub, String username, String tenant, String... roles) {
        return oidcLogin()
                .idToken(t -> t.subject(sub).claim("preferred_username", username).claim("name", "Nom " + username)
                        .claim("tenant", tenant))
                .authorities(Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    private JsonNode call(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body, int expected)
            throws Exception {
        req.with(who).with(csrf());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }

    private String userId(RequestPostProcessor who, String username) throws Exception {
        call(get("/api/me"), who, null, 200); // provisionnement
        JsonNode users = call(get("/api/users").param("q", username), who, null, 200);
        for (JsonNode u : users) {
            if (u.path("username").asText().equals(username)) {
                return u.path("id").asText();
            }
        }
        throw new AssertionError("utilisateur absent : " + username);
    }

    private String siteId(RequestPostProcessor who) throws Exception {
        return call(get("/api/me"), who, null, 200).path("sites").get(0).path("id").asText();
    }

    private JsonNode createNc(RequestPostProcessor who, String title) throws Exception {
        String body = """
                {"type":"NC","siteId":"%s","title":"%s","description":"Constat","severity":"majeure","data":{"cost":250}}
                """.formatted(siteId(who), title);
        return call(post("/api/records"), who, body, 201);
    }

    private JsonNode transition(String id, String code, RequestPostProcessor who, int expected) throws Exception {
        return call(post("/api/records/" + id + "/transitions/" + code), who, "{\"comment\":\"ok " + code + "\"}",
                expected);
    }

    @Test
    void parcoursNcCompletDeLaDeclarationALaCloture() throws Exception {
        String terrainId = userId(TERRAIN, "r.terrain");
        String qualiteId = userId(QUALITE, "r.qualite");

        JsonNode nc = createNc(TERRAIN, "Fuite presse");
        String id = nc.path("id").asText();
        assertThat(nc.path("reference").asText()).matches("NC-\\d{4}-\\d{4}");
        assertThat(nc.path("status").asText()).isEqualTo("DECLAREE");
        assertThat(nc.path("statusLabel").asText()).isEqualTo("Déclarée");
        assertThat(nc.path("availableTransitions")).isEmpty(); // FIELD_USER ne qualifie pas

        // refus : rôle, état, champs requis
        JsonNode forbidden = transition(id, "qualifier", TERRAIN, 403);
        assertThat(forbidden.path("error").asText()).isEqualTo("forbidden");
        JsonNode wrongState = transition(id, "cloturer", QUALITE, 409);
        assertThat(wrongState.path("error").asText()).isEqualTo("transition_impossible");
        JsonNode missing = transition(id, "qualifier", QUALITE, 409);
        assertThat(missing.path("message").asText()).contains("responsable");
        assertThat(missing.path("details").path("missing").toString()).contains("responsable");
        JsonNode view = call(get("/api/records/" + id), QUALITE, null, 200);
        assertThat(view.path("availableTransitions").toString()).contains("qualifier").contains("responsable");

        call(patch("/api/records/" + id), QUALITE, "{\"ownerId\":\"" + terrainId + "\"}", 200);
        transition(id, "qualifier", QUALITE, 200);
        transition(id, "lancer_analyse", QUALITE, 200);
        JsonNode noCause = transition(id, "valider_analyse", QUALITE, 409);
        assertThat(noCause.path("message").asText()).contains("Cause racine");
        call(patch("/api/records/" + id), QUALITE,
                "{\"data\":{\"causeRacine\":\"Joint usé\",\"analyse5p\":[\"pourquoi 1\"]}}", 200);
        transition(id, "valider_analyse", QUALITE, 200);

        // vérification impossible sans action, puis tant que les actions ne sont pas faites
        JsonNode noAction = transition(id, "lancer_verification", QUALITE, 409);
        assertThat(noAction.path("message").asText()).contains("au moins une action");
        JsonNode action = call(post("/api/records/" + id + "/actions"), QUALITE, """
                {"actionType":"corrective","title":"Changer le joint","ownerId":"%s","dueDate":"%s"}
                """.formatted(terrainId, LocalDate.now().plusDays(5)), 201);
        String actionId = action.path("id").asText();
        assertThat(action.path("actionType").asText()).isEqualTo("corrective");
        assertThat(action.path("status").asText()).isEqualTo("A_FAIRE");
        JsonNode notDone = transition(id, "lancer_verification", QUALITE, 409);
        assertThat(notDone.path("error").asText()).isEqualTo("requirements_missing");
        assertThat(notDone.path("message").asText()).contains("actions non terminées (1)");

        // l'action apparaît dans « Mes actions » du responsable
        JsonNode tasks = call(get("/api/my-tasks"), TERRAIN, null, 200);
        assertThat(tasks.toString()).contains(actionId);

        transition(actionId, "demarrer", TERRAIN, 200);
        transition(actionId, "terminer", TERRAIN, 200);
        transition(id, "lancer_verification", QUALITE, 200);
        JsonNode closed = transition(id, "cloturer", QUALITE, 200);
        assertThat(closed.path("status").asText()).isEqualTo("CLOTUREE");
        assertThat(closed.path("children").get(0).path("status").asText()).isEqualTo("FAITE");

        JsonNode locked = call(patch("/api/records/" + id), QUALITE, "{\"title\":\"x\"}", 409);
        assertThat(locked.path("error").asText()).isEqualTo("record_locked");

        // historique immuable : création, modifications (diff), transitions avec commentaire
        JsonNode history = call(get("/api/records/" + id + "/history"), QUALITE, null, 200);
        assertThat(history.toString()).contains("RECORD_CREATED").contains("RECORD_UPDATED").contains("causeRacine");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.path("transition").asText()).isEqualTo("cloturer");
        assertThat(last.path("comment").asText()).isEqualTo("ok cloturer");
        assertThat(last.path("author").asText()).isEqualTo("Nom r.qualite");
        JsonNode fiche = call(get("/api/records/" + id), QUALITE, null, 200);
        assertThat(fiche.path("createdByName").asText()).startsWith("Nom r.");
        assertThat(last.path("diff").path("status").path("to").asText()).isEqualTo("CLOTUREE");

        // notifications du responsable : assignation (NC + action) et transitions
        JsonNode notifs = call(get("/api/notifications"), TERRAIN, null, 200);
        assertThat(notifs.path("items").findValuesAsText("type")).contains("ASSIGNED", "TRANSITION");
        assertThat(notifs.path("items").get(0).path("link").asText()).startsWith("/records/");
        // le responsable qualité est notifié des étapes qui l'attendent (action faite : vérification par rôle QM)
        JsonNode notifsQualite = call(get("/api/notifications").param("unread", "true"), QUALITE, null, 200);
        assertThat(notifsQualite.path("items").findValuesAsText("recordId")).contains(actionId);
        call(post("/api/notifications/read-all"), QUALITE, null, 204);
        assertThat(call(get("/api/notifications").param("unread", "true"), QUALITE, null, 200)
                .path("unread").asLong()).isZero();
        assertThat(qualiteId).isNotBlank();

        JsonNode dashboard = call(get("/api/dashboard/summary"), QUALITE, null, 200);
        assertThat(dashboard.has("ncOpen") && dashboard.has("ncToQualify") && dashboard.has("actionsOverdue")
                && dashboard.has("ncCostMonth")).isTrue();
        assertThat(dashboard.path("ncCostMonth").asDouble()).isGreaterThanOrEqualTo(250);
    }

    @Test
    void listePagineeFiltreeEtBornee() throws Exception {
        createNc(TERRAIN, "Liste-zeta");
        JsonNode page = call(get("/api/records").param("type", "NC").param("q", "zeta").param("size", "5"),
                QUALITE, null, 200);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(5);
        assertThat(page.path("items").get(0).path("statusLabel").asText()).isEqualTo("Déclarée");
        call(get("/api/records").param("size", "101"), QUALITE, null, 400);
        call(get("/api/records").param("severity", "enorme"), QUALITE, null, 400);
    }

    @Test
    void validationDesEntreesEtLectureSeule() throws Exception {
        JsonNode bad = call(post("/api/records"), TERRAIN, "{\"type\":\"NC\",\"title\":\"\"}", 400);
        assertThat(bad.path("error").asText()).isEqualTo("invalid");
        assertThat(bad.path("details").path("fields").has("siteId")).isTrue();
        call(post("/api/records"), TERRAIN, """
                {"type":"NC","siteId":"%s","title":"x","severity":"enorme"}""".formatted(siteId(TERRAIN)), 400);
        call(post("/api/records"), LECTEUR, """
                {"type":"NC","siteId":"%s","title":"x"}""".formatted(siteId(LECTEUR)), 403);
        call(post("/api/records"), TERRAIN, """
                {"type":"INCONNU","siteId":"%s","title":"x"}""".formatted(siteId(TERRAIN)), 404);
        call(post("/api/records"), TERRAIN, """
                {"type":"NC","siteId":"00000000-0000-0000-0000-000000000000","title":"x"}""", 400);
    }

    @Test
    void uneFicheDUnAutreTenantEstInvisible() throws Exception {
        try (var c = ownerConnection(); var st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('RLS-REC', 'Autre') on conflict do nothing");
            st.execute("insert into site (tenant_id, code, name) select id, 'SIEGE', 'Siège' from tenant "
                    + "where code = 'RLS-REC' on conflict do nothing");
        }
        RequestPostProcessor autre = user("sub-r-autre", "r.autre", "RLS-REC", "ADMIN", "QUALITY_MANAGER");
        String id = createNc(TERRAIN, "Secret DEMO").path("id").asText();

        call(get("/api/records/" + id), autre, null, 404);
        call(post("/api/records/" + id + "/transitions/qualifier"), autre, "{}", 404);
        call(patch("/api/records/" + id), autre, "{\"title\":\"pirate\"}", 404);
        JsonNode list = call(get("/api/records").param("size", "100"), autre, null, 200);
        assertThat(list.path("total").asLong()).isZero();
        // le nouveau tenant reçoit les types par défaut et numérote indépendamment
        JsonNode own = createNc(autre, "Ma NC");
        assertThat(own.path("reference").asText()).endsWith("-0001");
        assertThat(call(get("/api/users"), autre, null, 200).findValuesAsText("username")).doesNotContain("r.terrain");
    }

    @Test
    void workflowLisibleEtModifiableParLAdminSeulement() throws Exception {
        JsonNode types = call(get("/api/record-types"), TERRAIN, null, 200);
        assertThat(types.findValuesAsText("code")).contains("NC", "ACTION");
        JsonNode wf = call(get("/api/record-types/NC/workflow"), TERRAIN, null, 200);
        assertThat(wf.path("initial").asText()).isEqualTo("DECLAREE");
        assertThat(wf.path("transitions").findValuesAsText("code")).contains("qualifier", "rejeter",
                "lancer_analyse", "valider_analyse", "renvoyer_analyse", "lancer_verification", "cloturer",
                "rouvrir_actions");

        call(put("/api/record-types/NC/workflow"), QUALITE, wf, 403);
        JsonNode invalid = call(put("/api/record-types/NC/workflow"), ADMIN,
                "{\"initial\":\"X\",\"states\":[{\"code\":\"A\",\"label\":\"A\",\"final\":false}],"
                        + "\"transitions\":[{\"code\":\"t\",\"label\":\"T\",\"from\":\"A\",\"to\":\"B\",\"roles\":[\"CHEF\"]}]}",
                400);
        assertThat(invalid.path("error").asText()).isEqualTo("invalid_workflow");
        assertThat(invalid.path("message").asText()).contains("initial").contains("état final");
        JsonNode saved = call(put("/api/record-types/NC/workflow"), ADMIN, wf, 200);
        assertThat(saved).isEqualTo(wf);
    }

    @Test
    void reglesDeNotificationAdmin() throws Exception {
        call(get("/api/notification-rules"), TERRAIN, null, 403);
        JsonNode rules = call(get("/api/notification-rules"), ADMIN, null, 200);
        assertThat(rules.size()).isEqualTo(18); // 9 événements (M3 et M4) x 2 canaux
        assertThat(rules.get(0).has("event") && rules.get(0).has("channel") && rules.get(0).has("enabled")).isTrue();
        JsonNode after = call(put("/api/notification-rules"), ADMIN,
                "[{\"event\":\"DUE_SOON\",\"channel\":\"EMAIL\",\"enabled\":true}]", 200);
        assertThat(after.toString()).contains("{\"event\":\"DUE_SOON\",\"channel\":\"EMAIL\",\"enabled\":true}");
        call(put("/api/notification-rules"), ADMIN, "[{\"event\":\"AUTRE\",\"channel\":\"EMAIL\",\"enabled\":true}]",
                400);
    }

    @Test
    void retardsEtEcheancesNotifiesUneSeuleFois() throws Exception {
        String terrainId = userId(TERRAIN, "r.terrain");
        String id = createNc(QUALITE, "Échéances").path("id").asText();
        LocalDate today = LocalDate.now(RecordService.ZONE);
        String late = call(post("/api/records/" + id + "/actions"), QUALITE, """
                {"actionType":"curative","title":"En retard","ownerId":"%s","dueDate":"%s"}
                """.formatted(terrainId, today.minusDays(1)), 201).path("id").asText();
        String soon = call(post("/api/records/" + id + "/actions"), QUALITE, """
                {"actionType":"preventive","title":"Bientôt","ownerId":"%s","dueDate":"%s"}
                """.formatted(terrainId, today.plusDays(2)), 201).path("id").asText();

        deadlines.run(today);
        deadlines.run(today); // pas de doublon

        JsonNode items = call(get("/api/notifications").param("size", "100"), TERRAIN, null, 200).path("items");
        long overdue = 0;
        long dueSoon = 0;
        for (JsonNode n : items) {
            if (n.path("recordId").asText().equals(late) && n.path("type").asText().equals("OVERDUE")) {
                overdue++;
            }
            if (n.path("recordId").asText().equals(soon) && n.path("type").asText().equals("DUE_SOON")) {
                dueSoon++;
            }
        }
        assertThat(overdue).isEqualTo(1);
        assertThat(dueSoon).isEqualTo(1);
        JsonNode tasks = call(get("/api/my-tasks"), TERRAIN, null, 200);
        for (JsonNode t : tasks) {
            if (t.path("id").asText().equals(late)) {
                assertThat(t.path("overdue").asBoolean()).isTrue();
            }
        }
        assertThat(call(get("/api/dashboard/summary"), QUALITE, null, 200).path("actionsOverdue").asLong())
                .isPositive();
        // marquer une notification lue
        String nid = items.get(0).path("id").asText();
        call(post("/api/notifications/" + nid + "/read"), TERRAIN, null, 204);
        call(post("/api/notifications/" + nid + "/read"), QUALITE, null, 404); // pas la sienne
    }

    @Test
    void fluxSseRecoitLesNotificationsEnTempsReel() throws Exception {
        String terrainId = userId(TERRAIN, "r.terrain");
        MvcResult stream = mvc.perform(get("/api/notifications/stream").with(TERRAIN)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String id = createNc(QUALITE, "Temps réel").path("id").asText();
        call(post("/api/records/" + id + "/actions"), QUALITE, """
                {"actionType":"curative","title":"Action SSE","ownerId":"%s","dueDate":"%s"}
                """.formatted(terrainId, LocalDate.now().plusDays(9)), 201);

        String body = stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:ready");
        assertThat(body).contains("event:notification");
        String data = body.substring(body.indexOf("event:notification"));
        data = data.substring(data.indexOf("data:") + 5, data.indexOf('\n', data.indexOf("data:")));
        JsonNode n = json.readTree(data);
        assertThat(n.path("type").asText()).isEqualTo("ASSIGNED");
        assertThat(n.has("id") && n.has("title") && n.has("recordId") && n.has("link") && n.has("read")
                && n.has("createdAt")).isTrue();
        stream.getRequest().getAsyncContext().complete();
    }

    @Test
    void sseSansAuthentificationRefuse() throws Exception {
        ResultActions r = mvc.perform(get("/api/notifications/stream"));
        r.andExpect(status().isUnauthorized());
        r.andExpect(jsonPathAbsent());
    }

    private static org.springframework.test.web.servlet.ResultMatcher jsonPathAbsent() {
        return result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("notification");
    }
}
