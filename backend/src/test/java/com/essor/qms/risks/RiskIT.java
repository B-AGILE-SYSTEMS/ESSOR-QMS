package com.essor.qms.risks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.essor.qms.ApiExamples;
import com.essor.qms.M4Support;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Contrat M4 risques : cotation et seuils, historique, matrice, action M1, DUER PDF, échelle, import, isolation. */
class RiskIT extends M4Support {

    private Map<String, Object> risk(String site, String hazard, Map<String, Integer> gross,
                                     Map<String, Integer> residual) {
        Map<String, Object> b = new HashMap<>(Map.of("type", "sst", "siteId", site, "workUnit", "Atelier cuisson",
                "process", "Production", "hazard", hazard, "situation", "Intervention sur four en marche",
                "existingMeasures", "Gants anti-chaleur", "gross", gross));
        if (residual != null) {
            b.put("residual", residual);
        }
        return b;
    }

    @Test
    void cotationSeuilsHistoriqueMatriceActionEtDuer() throws Exception {
        String site = siteId(QUALITE);
        String hazard = uniq("Brûlure four");
        JsonNode r = call(post("/api/risks"), QUALITE, risk(site, hazard, Map.of("gravite", 4, "probabilite", 3,
                "maitrise", 2), null), 201);
        assertThat(r.path("gross").path("score").asInt()).isEqualTo(12);
        assertThat(r.path("gross").path("level").asText()).isEqualTo("critique");
        assertThat(r.path("gross").path("color").asText()).isEqualTo("#C62828");
        assertThat(r.path("reference").asText()).startsWith("RSK-");
        String id = r.path("id").asText();

        // seuils : 2×2 = 4 → moyen, 1×3 = 3 → faible
        assertThat(call(post("/api/risks"), QUALITE, risk(site, uniq("Chute"), Map.of("gravite", 2,
                "probabilite", 2), Map.of("gravite", 1, "probabilite", 3)), 201).path("residual").path("level")
                .asText()).isEqualTo("faible");
        call(post("/api/risks"), QUALITE, risk(site, "X", Map.of("gravite", 5, "probabilite", 1), null), 400);
        call(post("/api/risks"), QUALITE, risk(site, "X", Map.of("gravite", 2), null), 400);
        call(post("/api/risks"), LECTEUR, risk(site, "X", Map.of("gravite", 2, "probabilite", 2), null), 403);

        // action de maîtrise = fiche ACTION M1
        JsonNode withAction = call(post("/api/risks/" + id + "/actions"), QUALITE, Map.of("title",
                "Installer un capot de protection", "dueDate", "2027-01-15"), 201);
        JsonNode action = withAction.path("actions").get(0);
        assertThat(action.path("reference").asText()).startsWith("ACT-");
        JsonNode rec = call(get("/api/records/" + action.path("id").asText()), QUALITE, null, 200);
        assertThat(rec.path("data").path("riskId").asText()).isEqualTo(id);

        // réévaluation : historique des cotations
        Map<String, Object> upd = risk(site, hazard, Map.of("gravite", 4, "probabilite", 3, "maitrise", 2),
                Map.of("gravite", 4, "probabilite", 1));
        upd.put("reassessmentDate", "2027-06-30");
        JsonNode after = call(put("/api/risks/" + id), QUALITE, upd, 200);
        assertThat(after.path("residual").path("score").asInt()).isEqualTo(4);
        assertThat(after.path("history")).extracting(h -> h.path("kind").asText()).containsExactly("gross",
                "residual");
        ApiExamples.write("risque", after);

        JsonNode matrix = call(get("/api/risks/matrix").param("siteId", site).param("type", "sst")
                .param("basis", "gross"), LECTEUR, null, 200);
        assertThat(matrix.path("cells").size()).isEqualTo(16);
        JsonNode cell = null;
        for (JsonNode c : matrix.path("cells")) {
            if (c.path("row").asInt() == 4 && c.path("col").asInt() == 3) {
                cell = c;
            }
        }
        assertThat(cell.path("level").asText()).isEqualTo("critique");
        assertThat(cell.path("risks")).extracting(x -> x.path("id").asText()).contains(id);
        assertThat(cell.path("count").asInt()).isEqualTo(cell.path("risks").size());
        ApiExamples.write("matrice-risques", matrix);

        byte[] pdf = perform(get("/api/risks/duer.pdf").param("siteId", site), LECTEUR, null, 200).getResponse()
                .getContentAsByteArray();
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("Document unique").contains(r.path("reference").asText());
    }

    @Test
    void echelleParametrableEtImportToutOuRien() throws Exception {
        JsonNode scales = call(get("/api/risk-scales"), LECTEUR, null, 200);
        assertThat(scales).extracting(s -> s.path("code").asText()).contains("DEFAUT");
        ApiExamples.write("echelles-risque", scales);
        Map<String, Object> scale = Map.of("label", "Environnement 3×3",
                "dimensions", List.of(
                        Map.of("code", "severite", "label", "Sévérité", "inFormula", true, "levels", List.of(
                                Map.of("value", 1, "label", "Faible"), Map.of("value", 2, "label", "Moyenne"),
                                Map.of("value", 3, "label", "Forte"))),
                        Map.of("code", "frequence", "label", "Fréquence", "inFormula", true, "levels", List.of(
                                Map.of("value", 1, "label", "Rare"), Map.of("value", 2, "label", "Mensuelle"),
                                Map.of("value", 3, "label", "Quotidienne")))),
                "thresholds", List.of(Map.of("level", "faible", "label", "Non significatif", "min", 1,
                        "color", "#2E7D32"), Map.of("level", "critique", "label", "Significatif", "min", 6,
                        "color", "#C62828")));
        call(put("/api/risk-scales/ENV"), TERRAIN, scale, 403);
        JsonNode env = call(put("/api/risk-scales/ENV"), QUALITE, scale, 200);
        assertThat(env.path("dimensions").size()).isEqualTo(2);
        String site = siteId(QUALITE);
        Map<String, Object> body = new HashMap<>(Map.of("type", "environnement", "siteId", site, "scaleCode", "ENV",
                "hazard", "Rejet d'eaux de lavage", "gross", Map.of("severite", 3, "frequence", 2)));
        assertThat(call(post("/api/risks"), QUALITE, body, 201).path("gross").path("levelLabel").asText())
                .isEqualTo("Significatif");

        String siteCode = call(get("/api/me"), QUALITE, null, 200).path("sites").get(0).path("code").asText();
        String bad = "type;site;danger;cotation_brute\nsst;" + siteCode + ";Bruit;gravite=2,probabilite=3\n"
                + "sst;INCONNU;Poussières;gravite=2,probabilite=3\nsst;" + siteCode + ";Chute;gravite=9\n";
        JsonNode rep = call(multipart("/api/risks/import").file(new MockMultipartFile("file", "r.csv", "text/csv",
                bad.getBytes(StandardCharsets.UTF_8))), QUALITE, null, 200);
        assertThat(rep.path("applied").asBoolean()).isFalse();
        assertThat(rep.path("errors")).extracting(e -> e.path("line").asInt()).containsExactly(3, 4);
        String ok = "type;site;danger;cotation_brute;cotation_residuelle\nsst;" + siteCode + ";" + uniq("Bruit")
                + ";gravite=2,probabilite=3;gravite=2,probabilite=1\n";
        JsonNode done = call(multipart("/api/risks/import").file(new MockMultipartFile("file", "r.csv", "text/csv",
                ok.getBytes(StandardCharsets.UTF_8))), QUALITE, null, 200);
        assertThat(done.path("applied").asBoolean()).isTrue();
        assertThat(done.path("created").asInt()).isEqualTo(1);
    }

    @Test
    void risquesInvisiblesDUnAutreTenant() throws Exception {
        String site = siteId(QUALITE);
        JsonNode r = call(post("/api/risks"), QUALITE, risk(site, uniq("Isolé"), Map.of("gravite", 1,
                "probabilite", 1), null), 201);
        otherTenant();
        call(get("/api/risks/" + r.path("id").asText()), AUTRE, null, 404);
        assertThat(call(get("/api/risks"), AUTRE, null, 200).size()).isZero();
    }
}
