package com.essor.qms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Décision métier M4 : le compte de démo « terrain » n'a que FIELD_USER (pas AUDITOR, qui décide des contrôles). */
class DemoTerrainSansAuditorTest {

    @Test
    void leCompteTerrainDuRealmNAQueFieldUser() throws Exception {
        JsonNode realm = new ObjectMapper().readTree(Path.of("../deploy/keycloak/realm-essor.json").toFile());
        JsonNode terrain = null;
        for (JsonNode u : realm.path("users")) {
            if ("terrain".equals(u.path("username").asText())) {
                terrain = u;
            }
        }
        assertThat(terrain).isNotNull();
        assertThat(terrain.path("realmRoles")).extracting(JsonNode::asText).containsExactly("FIELD_USER");
    }
}
