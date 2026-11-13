package com.essor.qms.partners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.essor.qms.ApiExamples;
import com.essor.qms.M4Support;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Contrat M4 évaluation partenaires : indicateurs automatiques, note, classe → plan de progrès, campagne, RLS. */
class PartnerEvaluationIT extends M4Support {

    private static String quarter() {
        LocalDate t = LocalDate.now();
        return t.getYear() + "-T" + ((t.getMonthValue() - 1) / 3 + 1);
    }

    @Test
    void scorecardAvecIndicateursAutomatiquesEtPlanDeProgres() throws Exception {
        String site = siteId(QUALITE);
        JsonNode sup = partner(uniq("FOU"), "fournisseur");
        String supId = sup.path("id").asText();
        JsonNode art = article(uniq("ART"));
        JsonNode l1 = call(post("/api/lots"), TERRAIN, Map.of("articleId", art.path("id").asText(), "numero",
                uniq("L"), "supplierId", supId, "quantity", 100, "siteId", site,
                "expectedDate", LocalDate.now().minusDays(3).toString()), 201);
        call(post("/api/lots"), TERRAIN, Map.of("articleId", art.path("id").asText(), "numero", uniq("L"),
                "supplierId", supId, "quantity", 100, "siteId", site), 201);
        call(post("/api/lots/" + l1.path("id").asText() + "/status"), QUALITE, Map.of("status", "REFUSE",
                "reason", "Humidité hors spécification"), 200);
        call(post("/api/records"), QUALITE, Map.of("type", "NC", "siteId", site, "title", "Farine humide",
                "data", Map.of("supplierId", supId)), 201);

        JsonNode grids = call(get("/api/evaluation-grids"), LECTEUR, null, 200);
        assertThat(grids).extracting(g -> g.path("code").asText()).contains("FOURNISSEUR", "CLIENT");
        ApiExamples.write("grilles-evaluation", grids);

        JsonNode ev = call(post("/api/partners/" + supId + "/evaluations"), QUALITE, Map.of("grid", "FOURNISSEUR",
                "period", quarter(), "scores", Map.of("qualite_service", 1, "reactivite", 1), "comment",
                "Trimestre difficile"), 201);
        JsonNode ind = ev.path("indicators");
        assertThat(ind.path("ncCount").asLong()).isEqualTo(1);
        assertThat(ind.path("lotsReceived").asLong()).isEqualTo(2);
        assertThat(ind.path("lotsRefusedPct").asDouble()).isEqualTo(50.0);
        assertThat(ind.path("lateDeliveries").asLong()).isEqualTo(1);
        // (3×1 + 2×1 + 2×3 + 2×0 + 1×2) / 4 / 10 × 100 = 32,5
        assertThat(ev.path("globalScore").asDouble()).isEqualTo(32.5);
        assertThat(ev.path("class").asText()).isEqualTo("C");
        assertThat(ev.path("action").path("reference").asText()).startsWith("ACT-");
        ApiExamples.write("evaluation-partenaire", ev);
        call(post("/api/partners/" + supId + "/evaluations"), QUALITE, Map.of("grid", "FOURNISSEUR",
                "period", quarter(), "scores", Map.of("qualite_service", 4, "reactivite", 4)), 409);
        call(post("/api/partners/" + supId + "/evaluations"), QUALITE, Map.of("grid", "CLIENT",
                "period", "2026-T1", "scores", Map.of()), 400);
        call(post("/api/partners/" + supId + "/evaluations"), QUALITE, Map.of("grid", "FOURNISSEUR",
                "period", "2026-Q1", "scores", Map.of()), 400);

        JsonNode card = call(get("/api/partners/" + supId + "/scorecard"), LECTEUR, null, 200);
        assertThat(card.path("history").size()).isEqualTo(1);
        assertThat(card.path("history").get(0).path("id").asText()).isEqualTo(ev.path("id").asText());
        assertThat(card.path("history").get(0).path("action").path("id").asText())
                .isEqualTo(ev.path("action").path("id").asText());
        assertThat(card.path("history").get(0).path("action").path("reference").asText()).startsWith("ACT-");
        assertThat(card.path("currentPeriod").path("indicators").path("ncCount").asLong()).isEqualTo(1);
        ApiExamples.write("scorecard", card);
        assertThat(call(get("/api/partners/" + supId + "/evaluations"), LECTEUR, null, 200).size()).isEqualTo(1);
    }

    @Test
    void campagneCreeLesEvaluationsAFairePuisSatisfactionClient() throws Exception {
        JsonNode c1 = partner(uniq("CLI"), "client");
        JsonNode c2 = partner(uniq("CLI"), "client");
        JsonNode camp = call(post("/api/evaluation-campaigns"), QUALITE, Map.of("grid", "CLIENT", "partnerIds",
                List.of(c1.path("id").asText(), c2.path("id").asText()), "period", "2026-S2"), 201);
        assertThat(camp.path("created").asInt()).isEqualTo(2);
        assertThat(camp.path("periodEnd").asText()).isEqualTo("2026-12-31");
        assertThat(camp.path("evaluations")).allSatisfy(e -> assertThat(e.path("status").asText())
                .isEqualTo("a_faire"));
        ApiExamples.write("campagne-evaluation", camp);
        call(post("/api/evaluation-campaigns"), TERRAIN, Map.of("grid", "CLIENT", "partnerIds",
                List.of(c1.path("id").asText()), "period", "2026-S2"), 403);
        String todo = camp.path("evaluations").get(0).path("id").asText();
        String pid = camp.path("evaluations").get(0).path("partner").path("id").asText();
        JsonNode ev = call(post("/api/partners/" + pid + "/evaluations"), QUALITE, Map.of("evaluationId", todo,
                "scores", Map.of("accueil", 5, "delais", 4, "qualite_produit", 5)), 201);
        assertThat(ev.path("id").asText()).isEqualTo(todo);
        assertThat(ev.path("status").asText()).isEqualTo("terminee");
        assertThat(ev.path("class").asText()).isEqualTo("A");
        assertThat(ev.path("action").isNull()).isTrue();

        otherTenant();
        call(get("/api/partners/" + pid + "/scorecard"), AUTRE, null, 404);
        assertThat(call(get("/api/partner-evaluations"), AUTRE, null, 200).size()).isZero();
    }
}
