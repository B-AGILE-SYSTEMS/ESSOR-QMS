package com.essor.qms.traceability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Contrat M3b côté serveur : données de base, lots, contrôles, décision → NC, généalogie, rappel, webhook, RLS. */
@AutoConfigureMockMvc
class TraceabilityIT extends AbstractIntegrationTest {

    static final RequestPostProcessor QUALITE =
            user("sub-b-qualite", "tb.qualite", "DEMO", "QUALITY_MANAGER");
    static final RequestPostProcessor ADMIN = user("sub-b-admin", "tb.admin", "DEMO", "ADMIN");
    static final RequestPostProcessor TERRAIN =
            user("sub-b-terrain", "tb.terrain", "DEMO", "FIELD_USER");
    static final RequestPostProcessor LECTEUR = user("sub-b-lecteur", "tb.lecteur", "DEMO", "READER");

    static RequestPostProcessor user(String sub, String username, String tenant, String... roles) {
        return oidcLogin()
                .idToken(t -> t.subject(sub).claim("preferred_username", username).claim("name", "Nom " + username)
                        .claim("tenant", tenant))
                .authorities(java.util.Arrays.stream(roles)
                        .<org.springframework.security.core.GrantedAuthority>map(
                                r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

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

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode article(String code, boolean dlc) throws Exception {
        return call(post("/api/master-data/articles"), QUALITE, Map.of("code", code, "label", "Article " + code,
                "family", "FAM-T", "unit", "kg", "tracking", "lot", "dlc", dlc), 201);
    }

    private JsonNode partner(String code, String type) throws Exception {
        return call(post("/api/master-data/partners"), QUALITE, Map.of("code", code, "name", "Nom " + code,
                "type", type), 201);
    }

    private JsonNode lot(JsonNode article, String numero, JsonNode supplier, double qty, String site)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("articleId", article.path("id").asText(),
                "numero", numero, "quantity", qty, "siteId", site));
        if (supplier != null) {
            body.put("supplierId", supplier.path("id").asText());
            body.put("supplierLot", "F-" + numero);
        }
        return call(post("/api/lots"), TERRAIN, body, 201);
    }

    private void link(JsonNode from, String kind, JsonNode target, JsonNode partner, double qty, String ref)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("kind", kind, "quantity", qty, "reference", ref));
        if (target != null) {
            body.put("targetLotId", target.path("id").asText());
        }
        if (partner != null) {
            body.put("partnerId", partner.path("id").asText());
        }
        call(post("/api/lots/" + from.path("id").asText() + "/links"), TERRAIN, body, 201);
    }

    private JsonNode plan(String point, JsonNode target, Map<String, Object> sampling, Object frequency)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", uniq("PC"));
        body.put("label", "Plan " + point);
        body.put("point", point);
        body.put("target", Map.of("type", "article", "id", target.path("id").asText()));
        body.put("characteristics", List.of(
                Map.of("code", "DIAM", "libelle", "Diamètre", "type", "mesure", "nominal", 10.0, "tolMin", 9.0,
                        "tolMax", 11.0, "unite", "mm", "instrumentRequis", "pied à coulisse", "criticite", "critique"),
                Map.of("code", "ASPECT", "libelle", "Aspect", "type", "attribut", "criticite", "mineure")));
        body.put("sampling", sampling);
        body.put("frequency", frequency);
        return call(post("/api/control-plans"), QUALITE, body, 201);
    }

    // ---------------------------------------------------------------- import

    @Test
    void importArticlesEtPartenairesAvecSimulation() throws Exception {
        String a1 = uniq("IMP");
        String csv = "code;designation;famille;unite;suivi;dlc\n" + a1 + ";Farine;MATIERE;kg;lot;oui\n"
                + uniq("IMP") + ";Sucre;MATIERE;kg;lot;non\n";
        MockMultipartFile f = new MockMultipartFile("file", "a.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        JsonNode dry = call(multipart("/api/master-data/articles/import").file(f).param("dryRun", "true"), QUALITE,
                null, 200);
        assertThat(dry.path("created").asInt()).isEqualTo(2);
        assertThat(dry.path("applied").asBoolean()).isFalse();
        assertThat(dry.path("dryRun").asBoolean()).isTrue();
        assertThat(call(get("/api/master-data/articles").param("q", a1), QUALITE, null, 200).size()).isZero();
        JsonNode done = call(multipart("/api/master-data/articles/import").file(f), QUALITE, null, 200);
        assertThat(done.path("applied").asBoolean()).isTrue();
        JsonNode list = call(get("/api/master-data/articles").param("q", a1), QUALITE, null, 200);
        assertThat(list.get(0).path("dlc").asBoolean()).isTrue();

        String bad = "code;nom;type\nP1;Fournisseur;fournisseur\n;Sans code;client\nP3;X;transporteur\n";
        MockMultipartFile fb = new MockMultipartFile("file", "p.csv", "text/csv", bad.getBytes(StandardCharsets.UTF_8));
        JsonNode rep = call(multipart("/api/master-data/partners/import").file(fb), QUALITE, null, 200);
        assertThat(rep.path("applied").asBoolean()).isFalse();
        assertThat(rep.path("errors")).extracting(e -> e.path("line").asInt()).containsExactly(3, 4);
        call(multipart("/api/master-data/partners/import").file(fb), TERRAIN, null, 403);
    }

    // ---------------------------------------------------------------- contrôle -> décision -> statut -> NC

    @Test
    void controleReceptionIsoPuisDecisionRefusCreeNcEtChangeStatut() throws Exception {
        String site = siteId(TERRAIN);
        JsonNode art = article(uniq("ART"), false);
        JsonNode sup = partner(uniq("FOU"), "fournisseur");
        JsonNode lot = lot(art, uniq("L"), sup, 1000, site);
        assertThat(lot.path("qualityStatus").asText()).isEqualTo("EN_ATTENTE");
        JsonNode plan = plan("RECEPTION", art, Map.of("mode", "iso2859", "nqa", 2.5, "niveau", "II"), null);

        JsonNode insp = call(post("/api/inspections"), TERRAIN, Map.of("lotId", lot.path("id").asText(),
                "controlPlanId", plan.path("id").asText(), "point", "RECEPTION"), 201);
        assertThat(insp.path("sampleSize").asInt()).isEqualTo(80);
        assertThat(insp.path("sampling").path("codeLetter").asText()).isEqualTo("J");
        assertThat(insp.path("sampling").path("ac").asInt()).isEqualTo(5);
        assertThat(insp.path("status").asText()).isEqualTo("A_FAIRE");
        String id = insp.path("id").asText();

        // Résultats : verdict calculé selon les tolérances, idempotence mobile.
        String key = UUID.randomUUID().toString();
        List<Map<String, Object>> results = List.of(
                Map.of("characteristic", "DIAM", "values", List.of(10.1, 11.5), "instrument", "PAC-01"),
                Map.of("characteristic", "ASPECT", "values", List.of(), "ok", true));
        JsonNode r1 = call(put("/api/inspections/" + id + "/results").header("Idempotency-Key", key), TERRAIN,
                results, 200);
        call(put("/api/inspections/" + id + "/results").header("Idempotency-Key", key), TERRAIN, results, 200);
        assertThat(r1.path("status").asText()).isEqualTo("EN_COURS");
        JsonNode diam = null;
        for (JsonNode r : r1.path("results")) {
            if (r.path("characteristic").asText().equals("DIAM")) {
                diam = r;
            }
        }
        assertThat(diam.path("ok").asBoolean()).isFalse();
        assertThat(diam.path("instrument").asText()).isEqualTo("PAC-01");
        assertThat(diam.path("planVersion").asInt()).isEqualTo(1);
        assertThat(diam.path("author").asText()).isNotBlank();
        assertThat(r1.path("proposedDecision").asText()).isEqualTo("REFUSE");

        call(post("/api/inspections/" + id + "/decision"), TERRAIN, Map.of("decision", "REFUSE"), 400);
        call(post("/api/inspections/" + id + "/decision"), LECTEUR, Map.of("decision", "REFUSE", "comment", "x"),
                403);
        JsonNode dec = call(post("/api/inspections/" + id + "/decision"), TERRAIN,
                Map.of("decision", "REFUSE", "comment", "Diamètre hors tolérance"), 200);
        assertThat(dec.path("status").asText()).isEqualTo("TERMINEE");
        String nc = dec.path("ncId").asText();
        assertThat(nc).isNotBlank();
        JsonNode record = call(get("/api/records/" + nc), QUALITE, null, 200);
        assertThat(record.path("type").asText()).isEqualTo("NC");
        assertThat(record.path("data").path("lotId").asText()).isEqualTo(lot.path("id").asText());
        assertThat(record.path("data").path("supplierId").asText()).isEqualTo(sup.path("id").asText());

        JsonNode detail = call(get("/api/lots/" + lot.path("id").asText()), TERRAIN, null, 200);
        assertThat(detail.path("lot").path("qualityStatus").asText()).isEqualTo("REFUSE");
        JsonNode last = detail.path("history").get(detail.path("history").size() - 1);
        assertThat(last.path("from").asText()).isEqualTo("EN_ATTENTE");
        assertThat(last.path("to").asText()).isEqualTo("REFUSE");
        assertThat(last.path("ncId").asText()).isEqualTo(nc);
        assertThat(detail.path("inspections").size()).isEqualTo(1);
        call(post("/api/inspections/" + id + "/decision"), TERRAIN, Map.of("decision", "LIBERE"), 409);
    }

    @Test
    void derogationExigeResponsableQualiteEtMotif() throws Exception {
        String site = siteId(TERRAIN);
        JsonNode art = article(uniq("ART"), false);
        JsonNode lot = lot(art, uniq("L"), null, 50, site);
        String id = lot.path("id").asText();
        call(post("/api/lots/" + id + "/status"), TERRAIN, Map.of("status", "DEROGATION", "reason", "ok client"),
                403);
        call(post("/api/lots/" + id + "/status"), QUALITE, Map.of("status", "DEROGATION"), 400);
        JsonNode ok = call(post("/api/lots/" + id + "/status"), QUALITE,
                Map.of("status", "DEROGATION", "reason", "Accord client écrit"), 200);
        assertThat(ok.path("qualityStatus").asText()).isEqualTo("DEROGATION");

        JsonNode plan = plan("PRODUCTION_SORTIE", art, Map.of("mode", "fixe", "taille", 5), null);
        JsonNode insp = call(post("/api/inspections"), TERRAIN, Map.of("lotId", id,
                "controlPlanId", plan.path("id").asText(), "point", "PRODUCTION_SORTIE"), 201);
        assertThat(insp.path("sampleSize").asInt()).isEqualTo(5);
        call(post("/api/inspections/" + insp.path("id").asText() + "/decision"), TERRAIN,
                Map.of("decision", "DEROGATION", "comment", "x"), 403);
        JsonNode d = call(post("/api/inspections/" + insp.path("id").asText() + "/decision"), QUALITE,
                Map.of("decision", "DEROGATION", "comment", "Dérogation client n°12"), 200);
        assertThat(d.path("ncId").asText()).isNotBlank();
    }

    @Test
    void planVersionneParPut() throws Exception {
        JsonNode art = article(uniq("ART"), false);
        JsonNode v1 = plan("RECEPTION", art, Map.of("mode", "fixe", "taille", 3), null);
        Map<String, Object> body = json.convertValue(v1, Map.class);
        body.put("label", "Plan modifié");
        JsonNode v2 = call(put("/api/control-plans/" + v1.path("id").asText()), QUALITE, body, 200);
        assertThat(v2.path("version").asInt()).isEqualTo(2);
        assertThat(v2.path("code").asText()).isEqualTo(v1.path("code").asText());
        assertThat(call(get("/api/control-plans/" + v1.path("id").asText()), QUALITE, null, 200)
                .path("active").asBoolean()).isFalse();
        call(put("/api/control-plans/" + v1.path("id").asText()), QUALITE, body, 409);
        call(post("/api/control-plans"), TERRAIN, body, 403);
    }

    // ---------------------------------------------------------------- SPC

    @Test
    void carteDeControleEnCours() throws Exception {
        String site = siteId(TERRAIN);
        JsonNode art = article(uniq("ART"), false);
        JsonNode lot = lot(art, uniq("L"), null, 500, site);
        JsonNode plan = plan("PRODUCTION_ENCOURS", art, Map.of("mode", "fixe", "taille", 5),
                Map.of("pieces", 50));
        double[] means = {10.0, 10.1, 9.9, 10.0, 10.05, 9.95, 10.0, 10.9};
        for (double m : means) {
            JsonNode insp = call(post("/api/inspections"), TERRAIN, Map.of("lotId", lot.path("id").asText(),
                    "controlPlanId", plan.path("id").asText(), "point", "PRODUCTION_ENCOURS"), 201);
            List<Double> values = List.of(m - 0.1, m, m + 0.1, m, m);
            call(put("/api/inspections/" + insp.path("id").asText() + "/results"), TERRAIN,
                    List.of(Map.of("characteristic", "DIAM", "values", values)), 200);
        }
        JsonNode spc = call(get("/api/inspections/spc").param("controlPlanId", plan.path("id").asText())
                .param("characteristic", "DIAM"), TERRAIN, null, 200);
        assertThat(spc.path("subgroupSize").asInt()).isEqualTo(5);
        assertThat(spc.path("points").size()).isEqualTo(8);
        assertThat(spc.path("limits").path("xbar").path("cl").asDouble()).isBetween(10.0, 10.2);
        assertThat(spc.path("cpk").isNumber()).isTrue();
        assertThat(spc.path("alerts")).extracting(a -> a.path("type").asText()).contains("hors_limites");
        call(get("/api/inspections/spc").param("controlPlanId", plan.path("id").asText())
                .param("characteristic", "ASPECT"), TERRAIN, null, 400);
    }

    // ---------------------------------------------------------------- généalogie, rappel, webhook

    @Test
    void genealogieMultiNiveauRappelBlocageEtWebhookSigne() throws Exception {
        List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wms", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            int n = calls.incrementAndGet();
            received.add(Map.of("body", new String(body, StandardCharsets.UTF_8),
                    "sig", String.valueOf(ex.getRequestHeaders().getFirst("X-Essor-Signature"))));
            int code = n == 1 ? 500 : 200; // premier envoi en échec : reprise
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
        String secret = "secret-de-test-hmac-0123456789";
        try {
            call(put("/api/connectors/webhook"), QUALITE, Map.of("url", "http://x", "secret", secret), 403);
            JsonNode cfg = call(put("/api/connectors/webhook"), ADMIN, Map.of("url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/wms", "secret", secret), 200);
            assertThat(cfg.path("secretSet").asBoolean()).isTrue();
            assertThat(cfg.has("secret")).isFalse();

            String site = siteId(TERRAIN);
            JsonNode mp = article(uniq("MP"), false);
            JsonNode pf = article(uniq("PF"), false);
            JsonNode sup = partner(uniq("FOU"), "fournisseur");
            JsonNode c1 = partner(uniq("CLI"), "client");
            JsonNode c2 = partner(uniq("CLI"), "client");
            JsonNode lf = lot(mp, uniq("LF"), sup, 100, site);                // lot fournisseur
            JsonNode semi = lot(pf, uniq("SEMI"), null, 80, site);            // niveau 1
            JsonNode fini = lot(pf, uniq("FINI"), null, 60, site);            // niveau 2
            JsonNode autre = lot(pf, uniq("AUTRE"), null, 10, site);          // hors rappel
            link(lf, "consumed_by", semi, null, 90, "OF-1");
            link(semi, "split_into", fini, null, 60, "OF-2");
            link(fini, "shipped_to", null, c1, 20, "BL-1");
            link(fini, "shipped_to", null, c2, 15, "BL-2 SSCC-0001");
            link(fini, "shipped_to", null, c1, 5, "BL-3");
            // Cycle refusé
            call(post("/api/lots/" + fini.path("id").asText() + "/links"), TERRAIN, Map.of("kind", "consumed_by",
                    "targetLotId", lf.path("id").asText(), "quantity", 1), 409);
            call(post("/api/lots/" + fini.path("id").asText() + "/links"), TERRAIN, Map.of("kind", "shipped_to",
                    "partnerId", sup.path("id").asText(), "quantity", 1), 400);

            JsonNode down = call(get("/api/lots/" + lf.path("id").asText() + "/trace").param("direction", "down"),
                    TERRAIN, null, 200);
            assertThat(down.path("lot").path("id").asText()).isEqualTo(lf.path("id").asText());
            JsonNode n1 = down.path("children").get(0);
            assertThat(n1.path("link").path("kind").asText()).isEqualTo("consumed_by");
            JsonNode n2 = n1.path("node").path("children").get(0);
            assertThat(n2.path("node").path("lot").path("id").asText()).isEqualTo(fini.path("id").asText());
            assertThat(n2.path("node").path("children").size()).isEqualTo(3);
            assertThat(n2.path("node").path("children").get(0).path("link").path("partner").path("code").asText())
                    .isEqualTo(c1.path("code").asText());
            JsonNode limited = call(get("/api/lots/" + lf.path("id").asText() + "/trace").param("depth", "1"),
                    TERRAIN, null, 200);
            assertThat(limited.path("children").get(0).path("node").path("children").size()).isZero();

            JsonNode up = call(get("/api/lots/" + fini.path("id").asText() + "/trace").param("direction", "up"),
                    TERRAIN, null, 200);
            JsonNode upLf = up.path("children").get(0).path("node").path("children").get(0).path("node");
            assertThat(upLf.path("lot").path("id").asText()).isEqualTo(lf.path("id").asText());
            assertThat(upLf.path("lot").path("supplier").path("id").asText()).isEqualTo(sup.path("id").asText());

            JsonNode recall = call(get("/api/lots/" + lf.path("id").asText() + "/recall"), TERRAIN, null, 200);
            assertThat(recall.path("lotsImpactes")).extracting(l -> l.path("id").asText())
                    .containsExactlyInAnyOrder(lf.path("id").asText(), semi.path("id").asText(),
                            fini.path("id").asText())
                    .doesNotContain(autre.path("id").asText());
            assertThat(recall.path("clients").size()).isEqualTo(2);
            JsonNode cl1 = recall.path("clients").get(0);
            assertThat(cl1.path("partner").path("id").asText()).isEqualTo(c1.path("id").asText());
            assertThat(cl1.path("quantite").asDouble()).isEqualTo(25.0);
            assertThat(cl1.path("references")).extracting(JsonNode::asText).containsExactly("BL-1", "BL-3");
            // restants : lf 100-90 = 10 ; semi 80-60 = 20 ; fini 60-40 = 20 ; tous EN_ATTENTE -> en cours
            assertThat(recall.path("quantites").path("expedie").asDouble()).isEqualTo(40.0);
            assertThat(recall.path("quantites").path("enCours").asDouble()).isEqualTo(50.0);
            assertThat(recall.path("quantites").path("enStock").asDouble()).isZero();

            byte[] pdf = perform(get("/api/lots/" + lf.path("id").asText() + "/recall.pdf"), TERRAIN, null, 200)
                    .getResponse().getContentAsByteArray();
            String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
            assertThat(text).contains("Dossier de rappel").contains(c2.path("code").asText());

            // Blocage du stock restant : 3 lots en QUARANTAINE, 3 webhooks.
            call(post("/api/lots/" + lf.path("id").asText() + "/recall/block"), TERRAIN,
                    Map.of("reason", "Défaut fournisseur"), 403);
            JsonNode blocked = call(post("/api/lots/" + lf.path("id").asText() + "/recall/block"), QUALITE,
                    Map.of("reason", "Défaut signalé par le fournisseur"), 200);
            assertThat(blocked.path("blocked").size()).isEqualTo(3);
            assertThat(call(get("/api/lots/" + fini.path("id").asText()), TERRAIN, null, 200)
                    .path("lot").path("qualityStatus").asText()).isEqualTo("QUARANTAINE");

            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            JsonNode deliveries;
            do {
                Thread.sleep(100);
                deliveries = call(get("/api/connectors/webhook/deliveries"), ADMIN, null, 200);
            } while (Instant.now().isBefore(deadline) && !allSent(deliveries, 3));
            assertThat(allSent(deliveries, 3)).as("3 envois SENT").isTrue();
            assertThat(deliveries).extracting(d -> d.path("attempts").asInt()).contains(2);
            assertThat(received.size()).isEqualTo(4);
            for (Map<String, Object> r : received) {
                String body = (String) r.get("body");
                assertThat(r.get("sig")).isEqualTo("sha256=" + WebhookService.sign(secret, body));
                JsonNode payload = json.readTree(body);
                assertThat(payload.path("event").asText()).isEqualTo("lot.status_changed");
                assertThat(payload.path("status").asText()).isEqualTo("QUARANTAINE");
                assertThat(payload.has("lotId") && payload.has("numero") && payload.has("article")
                        && payload.has("reason") && payload.has("at")).isTrue();
            }
        } finally {
            server.stop(0);
            call(put("/api/connectors/webhook"), ADMIN, Map.of("url", "http://127.0.0.1:9/off", "enabled", false),
                    200);
        }
    }

    private static boolean allSent(JsonNode deliveries, int n) {
        int sent = 0;
        for (JsonNode d : deliveries) {
            if ("SENT".equals(d.path("status").asText())) {
                sent++;
            }
        }
        return sent >= n;
    }

    @Test
    void webhookEnEchecApresTroisEssais() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ko", ex -> {
            calls.incrementAndGet();
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
        try {
            call(put("/api/connectors/webhook"), ADMIN, Map.of("url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/ko", "secret",
                    "autre-secret-hmac-0123456789", "enabled", true), 200);
            JsonNode lot = lot(article(uniq("ART"), false), uniq("L"), null, 5, siteId(TERRAIN));
            call(post("/api/lots/" + lot.path("id").asText() + "/status"), QUALITE,
                    Map.of("status", "LIBERE"), 200);
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            JsonNode d;
            do {
                Thread.sleep(100);
                d = call(get("/api/connectors/webhook/deliveries").param("limit", "1"), ADMIN, null, 200).get(0);
            } while (Instant.now().isBefore(deadline) && !"FAILED".equals(d.path("status").asText()));
            assertThat(d.path("status").asText()).isEqualTo("FAILED");
            assertThat(d.path("attempts").asInt()).isEqualTo(3);
            assertThat(d.path("lastCode").asInt()).isEqualTo(503);
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            server.stop(0);
            call(put("/api/connectors/webhook"), ADMIN, Map.of("url", "http://127.0.0.1:9/off", "enabled", false),
                    200);
        }
    }

    // ---------------------------------------------------------------- volume

    /** 10 → 100 → 1000 lots (3 niveaux) puis 9 expéditions par lot du dernier niveau : 10 110 liens. */
    @Test
    void rappelSurDixMilleLiensEnMoinsDUneSeconde() throws Exception {
        siteId(QUALITE);
        String tag = uniq("VOL");
        try (Connection c = ownerConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    do $$
                    declare t uuid := '00000000-0000-0000-0000-00000000d001'; a uuid; root uuid;
                    begin
                      insert into article (tenant_id, code, label) values (t, '%1$s', 'Volume') returning id into a;
                      insert into partner (tenant_id, code, name, type)
                        select t, '%1$s-C' || g, 'Client ' || g, 'client' from generate_series(1, 50) g;
                      insert into lot (tenant_id, article_id, numero, quantity, unit, quality_status)
                        values (t, a, '%1$s-ROOT', 100000, 'u', 'LIBERE') returning id into root;
                      insert into lot (tenant_id, article_id, numero, quantity, unit, quality_status)
                        select t, a, '%1$s-N' || lvl || '-' || g, 1000, 'u', 'LIBERE'
                        from (values (1, 10), (2, 100), (3, 1000)) v(lvl, n), generate_series(1, n) g;
                      insert into lot_link (tenant_id, source_lot_id, kind, target_lot_id, quantity, reference)
                        select t, root, 'consumed_by', id, 10, 'OF' from lot
                        where tenant_id = t and numero like '%1$s-N1-%%';
                      insert into lot_link (tenant_id, source_lot_id, kind, target_lot_id, quantity, reference)
                        select t, p.id, 'consumed_by', ch.id, 1, 'OF'
                        from lot ch join lot p on p.tenant_id = t
                          and p.numero = '%1$s-N1-' || ((split_part(ch.numero, '-', 4)::int - 1) / 10 + 1)
                        where ch.tenant_id = t and ch.numero like '%1$s-N2-%%';
                      insert into lot_link (tenant_id, source_lot_id, kind, target_lot_id, quantity, reference)
                        select t, p.id, 'split_into', ch.id, 1, 'OF'
                        from lot ch join lot p on p.tenant_id = t
                          and p.numero = '%1$s-N2-' || ((split_part(ch.numero, '-', 4)::int - 1) / 10 + 1)
                        where ch.tenant_id = t and ch.numero like '%1$s-N3-%%';
                      insert into lot_link (tenant_id, source_lot_id, kind, partner_id, quantity, reference)
                        select t, l.id, 'shipped_to', pa.id, 1, 'BL-' || l.numero || '-' || g
                        from lot l cross join generate_series(1, 9) g
                        join partner pa on pa.tenant_id = t and pa.code = '%1$s-C' || (g * 5)
                        where l.tenant_id = t and l.numero like '%1$s-N3-%%';
                    end $$""".formatted(tag));
        }
        String rootId;
        long links;
        try (Connection c = ownerConnection();
             PreparedStatement ps = c.prepareStatement("select id from lot where numero = ?")) {
            ps.setString(1, tag + "-ROOT");
            var rs = ps.executeQuery();
            rs.next();
            rootId = rs.getString(1);
            var rs2 = c.createStatement().executeQuery("select count(*) from lot_link l join lot s on s.id = "
                    + "l.source_lot_id where s.numero like '" + tag + "-%'");
            rs2.next();
            links = rs2.getLong(1);
        }
        assertThat(links).isGreaterThanOrEqualTo(10_000);
        call(get("/api/lots/" + rootId), QUALITE, null, 200); // chauffe
        long t0 = System.nanoTime();
        JsonNode recall = call(get("/api/lots/" + rootId + "/recall"), QUALITE, null, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(recall.path("lotsImpactes").size()).isEqualTo(1111);
        assertThat(recall.path("clients").size()).isEqualTo(9);
        assertThat(recall.path("quantites").path("expedie").asDouble()).isEqualTo(9000.0);
        assertThat(ms).as("rappel en %d ms", ms).isLessThan(1000);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void lotsPlansEtPartenairesInvisiblesDUnAutreTenant() throws Exception {
        JsonNode art = article(uniq("ART"), false);
        JsonNode lot = lot(art, uniq("L"), null, 10, siteId(TERRAIN));
        JsonNode plan = plan("RECEPTION", art, Map.of("mode", "fixe", "taille", 2), null);
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('RLS-M3B', 'Autre') on conflict do nothing");
            st.execute("insert into site (tenant_id, code, name) select id, 'S', 'Site' from tenant "
                    + "where code = 'RLS-M3B' on conflict do nothing");
        }
        RequestPostProcessor autre = user("sub-b-autre", "tb.autre", "RLS-M3B", "ADMIN",
                "QUALITY_MANAGER");
        call(get("/api/lots/" + lot.path("id").asText()), autre, null, 404);
        call(get("/api/lots/" + lot.path("id").asText() + "/recall"), autre, null, 404);
        call(get("/api/control-plans/" + plan.path("id").asText()), autre, null, 404);
        assertThat(call(get("/api/lots"), autre, null, 200).size()).isZero();
        assertThat(call(get("/api/master-data/articles"), autre, null, 200).size()).isZero();
        assertThat(call(get("/api/control-plans"), autre, null, 200).size()).isZero();
        call(post("/api/lots"), autre, Map.of("articleId", art.path("id").asText(), "numero", "X", "quantity", 1),
                400);
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("select count(*) from pg_class where relname in ('article','partner','lot',"
                    + "'lot_status_history','lot_link','control_plan','inspection','inspection_result',"
                    + "'webhook_config','webhook_delivery') and relrowsecurity and relforcerowsecurity");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(10);
        }
    }
}
