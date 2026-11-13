package com.essor.qms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Outils communs des tests d'intégration M4 (utilisateurs OIDC simulés, appels JSON, autre tenant). */
@AutoConfigureMockMvc
public abstract class M4Support extends AbstractIntegrationTest {

    protected static final RequestPostProcessor QUALITE = user("sub-m4-qualite", "m4.qualite", "DEMO",
            "QUALITY_MANAGER");
    protected static final RequestPostProcessor TERRAIN = user("sub-m4-terrain", "m4.terrain", "DEMO", "FIELD_USER");
    protected static final RequestPostProcessor LECTEUR = user("sub-m4-lecteur", "m4.lecteur", "DEMO", "READER");
    /** Utilisateur d'un autre tenant (créé par {@link #otherTenant()}). */
    protected static final RequestPostProcessor AUTRE = user("sub-m4-autre", "m4.autre", "M4-AUTRE",
            "QUALITY_MANAGER");

    protected static RequestPostProcessor user(String sub, String username, String tenant, String... roles) {
        return oidcLogin()
                .idToken(t -> t.subject(sub).claim("preferred_username", username).claim("name", "Nom " + username)
                        .claim("tenant", tenant))
                .authorities(Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    protected MvcResult perform(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body,
                                int expected) throws Exception {
        req.with(who).with(csrf());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req).andExpect(status().is(expected)).andReturn();
    }

    protected JsonNode call(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body, int expected)
            throws Exception {
        String text = perform(req, who, body, expected).getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? null : json.readTree(text);
    }

    protected String siteId(RequestPostProcessor who) throws Exception {
        return call(get("/api/me"), who, null, 200).path("sites").get(0).path("id").asText();
    }

    protected static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static void otherTenant() throws Exception {
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('M4-AUTRE', 'Autre M4') on conflict do nothing");
            st.execute("insert into site (tenant_id, code, name) select id, 'S-AUTRE', 'Site autre' from tenant "
                    + "where code = 'M4-AUTRE' on conflict do nothing");
        }
    }

    protected JsonNode partner(String code, String type) throws Exception {
        return call(post("/api/master-data/partners"), QUALITE, Map.of("code", code, "name", "Nom " + code,
                "type", type), 201);
    }

    protected JsonNode article(String code) throws Exception {
        return call(post("/api/master-data/articles"), QUALITE, Map.of("code", code, "label", "Article " + code,
                "family", "FAM-T", "unit", "kg", "tracking", "lot", "dlc", false), 201);
    }

    /** Plan de contrôle à une mesure (DIAM 9..11 mm) et un attribut. */
    protected JsonNode plan(JsonNode article) throws Exception {
        return call(post("/api/control-plans"), QUALITE, Map.of("code", uniq("PC"), "label", "Plan réception",
                "point", "RECEPTION", "target", Map.of("type", "article", "id", article.path("id").asText()),
                "characteristics", List.of(Map.of("code", "DIAM", "libelle", "Diamètre", "type", "mesure",
                        "nominal", 10.0, "tolMin", 9.0, "tolMax", 11.0, "unite", "mm", "criticite", "majeure")),
                "sampling", Map.of("mode", "iso2859", "nqa", 2.5, "niveau", "II")), 201);
    }
}
