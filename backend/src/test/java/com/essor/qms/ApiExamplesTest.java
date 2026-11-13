package com.essor.qms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Comparaison de STRUCTURE des exemples d'API : les valeurs changent, pas les clés ni les types. */
class ApiExamplesTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void valeursDifferentesMemeStructureAcceptees() throws Exception {
        assertThat(ApiExamples.compare(json.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}],\"e\":null}"),
                json.readTree("{\"a\":2.5,\"b\":\"y\",\"c\":[{\"d\":false},{\"d\":true}],\"e\":\"z\"}"))).isEmpty();
    }

    @Test
    void cleManquanteNouvelleOuTypeChangeSignales() throws Exception {
        assertThat(ApiExamples.compare(json.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}]}"),
                json.readTree("{\"a\":\"1\",\"c\":[{\"d\":true,\"f\":1}],\"g\":0}")))
                .containsExactlyInAnyOrder("$.a : nombre attendu, texte reçu", "$.b : clé absente de la réponse",
                        "$.c[0].f : clé nouvelle, absente de l'exemple", "$.g : clé nouvelle, absente de l'exemple");
    }
}
