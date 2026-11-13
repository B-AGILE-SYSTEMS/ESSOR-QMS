package com.essor.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** Jeu de démo M4 (profil demo) : les quatre scénarios du contrat sont jouables. */
@ActiveProfiles({"test", "demo"})
class M4DemoIT extends M4Support {

    static final String DES02 = "00000000-0000-0000-0000-00000000e101";
    static final String USINE = "00000000-0000-0000-0000-0000000051a1";

    @Test
    void quatreScenariosDeDemo() throws Exception {
        // (1) instrument bloqué → contrôle et lot depuis le dernier étalonnage conforme
        JsonNode impact = call(get("/api/instruments/" + DES02 + "/impact"), QUALITE, null, 200);
        assertThat(impact.path("instrument").path("status").asText()).isEqualTo("bloque");
        assertThat(impact.path("lots")).extracting(l -> l.path("numero").asText()).contains("LF-2026-0311");
        // (2) risque critique de l'usine avec action M1
        JsonNode matrix = call(get("/api/risks/matrix").param("siteId", USINE).param("type", "sst"), QUALITE, null,
                200);
        boolean found = false;
        for (JsonNode c : matrix.path("cells")) {
            for (JsonNode r : c.path("risks")) {
                if ("RSK-0001".equals(r.path("reference").asText())) {
                    found = "critique".equals(r.path("level").asText());
                    JsonNode risk = call(get("/api/risks/" + r.path("id").asText()), QUALITE, null, 200);
                    assertThat(risk.path("actions").get(0).path("reference").asText()).startsWith("ACT-");
                }
            }
        }
        assertThat(found).isTrue();
        // (3) texte non conforme avec plan d'action, taux de l'usine
        JsonNode comp = call(get("/api/regulations/compliance").param("siteId", USINE), QUALITE, null, 200);
        assertThat(comp.path("sites").get(0).path("nonConforme").asLong()).isPositive();
        assertThat(call(get("/api/regulations").param("siteId", USINE), QUALITE, null, 200).size())
                .isGreaterThanOrEqualTo(8);
        // (4) scorecard fournisseur : historique et indicateurs automatiques du trimestre
        JsonNode card = call(get("/api/partners/00000000-0000-0000-0000-00000000b201/scorecard"), QUALITE, null, 200);
        assertThat(card.path("history").size()).isEqualTo(1);
        JsonNode ind = card.path("currentPeriod").path("indicators");
        assertThat(ind.path("ncCount").asLong()).isPositive();
        assertThat(ind.path("lotsRefused").asLong()).isPositive();
        assertThat(ind.path("lateDeliveries").asLong()).isPositive();
        assertThat(call(get("/api/partner-evaluations").param("status", "a_faire"), QUALITE, null, 200).size())
                .isGreaterThanOrEqualTo(3);
    }
}
