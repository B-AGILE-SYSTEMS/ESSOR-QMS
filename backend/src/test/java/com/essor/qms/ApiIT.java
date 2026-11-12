package com.essor.qms;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
@Import(ApiIT.TestJwt.class)
class ApiIT extends AbstractIntegrationTest {

    /**
     * Décodeur de test : le jeton "sub~username~tenant~ROLE1+ROLE2" devient un JWT Keycloak.
     * La chaîne Bearer réelle (filtre, conversion des rôles realm) est ainsi exercée.
     */
    @TestConfiguration
    static class TestJwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String[] p = token.split("~", -1);
                if (p.length != 4) {
                    throw new BadJwtException("jeton invalide");
                }
                Jwt.Builder b = Jwt.withTokenValue(token).header("alg", "none")
                        .subject(p[0]).claim("preferred_username", p[1]).claim("name", p[1])
                        .claim("realm_access", Map.of("roles", List.of(p[3].split("\\+"))))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
                if (!p[2].isEmpty()) {
                    b.claim("tenant", p[2]);
                }
                return b.build();
            };
        }
    }

    @Autowired
    MockMvc mvc;

    static RequestPostProcessor bff(String sub, String username, String... roles) {
        return oidcLogin()
                .idToken(t -> t.subject(sub).claim("preferred_username", username).claim("name", "Nom " + username))
                .authorities(java.util.Arrays.stream(roles).<org.springframework.security.core.GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList());
    }

    static String bearer(String sub, String username, String tenant, String roles) {
        return "Bearer " + sub + "~" + username + "~" + tenant + "~" + roles;
    }

    @Test
    void santePublique() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void apiSansAuthentificationRenvoie401EtPasDeRedirection() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void connexionBffDemarreParKeycloak() throws Exception {
        mvc.perform(get("/oauth2/authorization/keycloak")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(
                        "http://localhost/auth/realms/essor/protocol/openid-connect/auth")));
    }

    @Test
    void meBffProvisionneLUtilisateurDansLeTenantParDefaut() throws Exception {
        mvc.perform(get("/api/me").with(bff("sub-qualite", "qualite", "QUALITY_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("qualite"))
                .andExpect(jsonPath("$.displayName").value("Nom qualite"))
                .andExpect(jsonPath("$.tenant.code").value("DEMO"))
                .andExpect(jsonPath("$.sites[*].code").value(hasItem("SIEGE")))
                .andExpect(jsonPath("$.roles").value(hasItem("QUALITY_MANAGER")));
        // second appel : pas de doublon (provisionnement idempotent)
        mvc.perform(get("/api/me").with(bff("sub-qualite", "qualite", "QUALITY_MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void meBearerMobileAvecRolesRealm() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-terrain", "terrain", "", "FIELD_USER+offline_access")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("terrain"))
                .andExpect(jsonPath("$.tenant.code").value("DEMO"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("FIELD_USER"));
    }

    @Test
    void bearerInvalideRenvoie401() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer n-importe-quoi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantInconnuRefuse() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-x", "x", "INCONNU", "READER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantsReservesAuxAdmins() throws Exception {
        mvc.perform(get("/api/tenants").with(bff("sub-lecteur", "lecteur", "READER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants").with(bff("sub-admin", "admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(hasItem("DEMO")));
    }

    @Test
    void creationTenantExigeLeCsrfEnModeBff() throws Exception {
        String body = "{\"code\":\"SANS-CSRF\",\"name\":\"x\"}";
        mvc.perform(post("/api/tenants").with(bff("sub-admin", "admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void unUtilisateurDUnAutreTenantNeVoitPasLesSitesDeDemo() throws Exception {
        mvc.perform(post("/api/tenants").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ACME\",\"name\":\"Acme SA\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ACME"));
        mvc.perform(post("/api/tenants").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ACME\",\"name\":\"Doublon\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/me").header("Authorization", bearer("sub-acme", "acme.user", "ACME", "READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.code").value("ACME"))
                .andExpect(jsonPath("$.sites.length()").value(1));

        // l'habillage modifié dans DEMO n'est pas visible dans ACME
        mvc.perform(put("/api/tenant/branding").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"#AA0000\",\"theme\":\"dark\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/branding").header("Authorization", bearer("sub-acme", "acme.user", "ACME", "READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value(not("#AA0000")));
    }

    @Test
    void brandingLectureEtModificationParAdmin() throws Exception {
        mvc.perform(put("/api/tenant/branding").with(bff("sub-lecteur", "lecteur", "READER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"#123456\",\"theme\":\"light\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/tenant/branding").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"rouge\",\"theme\":\"light\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/tenant/branding").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"#123456\",\"theme\":\"light\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#123456"))
                .andExpect(jsonPath("$.theme").value("light"));
    }

    @Test
    void logoPngTeleverseEtServi() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", png))
                        .with(bff("sub-admin", "admin", "ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value("/api/tenant/branding/logo"));
        mvc.perform(get("/api/tenant/branding/logo").with(bff("sub-lecteur", "lecteur", "READER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png));
    }

    @Test
    void logoRefuseSiActifTropGrosOuMauvaisFormat() throws Exception {
        byte[] evil = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "logo.svg", "image/svg+xml", evil))
                        .with(bff("sub-admin", "admin", "ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "logo.gif", "image/gif", "GIF89a".getBytes()))
                        .with(bff("sub-admin", "admin", "ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[600 * 1024]))
                        .with(bff("sub-admin", "admin", "ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "logo.svg", "image/svg+xml", "<svg/>".getBytes()))
                        .with(bff("sub-lecteur", "lecteur", "READER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deconnexionXhrRenvoieLUrlKeycloak() throws Exception {
        mvc.perform(post("/logout").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoutUrl").value(org.hamcrest.Matchers.startsWith(
                        "http://localhost/auth/realms/essor/protocol/openid-connect/logout?client_id=essor-bff")));
    }
}
