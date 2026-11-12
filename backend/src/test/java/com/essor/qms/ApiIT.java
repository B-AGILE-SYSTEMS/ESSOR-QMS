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
     * Décodeur de test : le jeton "sub~username~tenant~ROLE1+ROLE2~azp" (ou "aud.<client>" en 5e partie) devient un JWT Keycloak.
     * La chaîne Bearer réelle (filtre, conversion des rôles realm) est ainsi exercée.
     */
    @TestConfiguration
    static class TestJwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String[] p = token.split("~", -1);
                if (p.length != 5) {
                    throw new BadJwtException("jeton invalide");
                }
                Jwt.Builder b = Jwt.withTokenValue(token).header("alg", "none")
                        .subject(p[0]).claim("preferred_username", p[1]).claim("name", p[1])
                        .claim("realm_access", Map.of("roles", List.of(p[3].split("\\+"))))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
                if (!p[2].isEmpty()) {
                    b.claim("tenant", p[2]);
                }
                if (p[4].startsWith("aud.")) {
                    b.audience(List.of(p[4].substring(4)));
                } else {
                    b.claim("azp", p[4]);
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
        return bearer(sub, username, tenant, roles, "essor-mobile");
    }

    static String bearer(String sub, String username, String tenant, String roles, String azpOrAud) {
        return "Bearer " + sub + "~" + username + "~" + tenant + "~" + roles + "~" + azpOrAud;
    }

    @Test
    void bearerDUnAutreClientRefuse() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-bff", "x", "", "READER", "essor-bff")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-aud", "x", "", "READER", "aud.essor-mobile")))
                .andExpect(status().isOk());
    }

    @Test
    void changementDeTenantDansKeycloakRefuseSansDoublon() throws Exception {
        try (var c = ownerConnection(); var st = c.createStatement()) {
            st.execute("insert into tenant (code, name) values ('AUTRE', 'Autre') on conflict do nothing");
        }
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-mobile", "mobile", "DEMO", "READER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-mobile", "mobile", "AUTRE", "READER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").header("Authorization", bearer("sub-mobile", "mobile", "DEMO", "READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.code").value("DEMO"));
        try (var c = ownerConnection(); var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from app_user where subject = 'sub-mobile'")) {
            rs.next();
            org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isEqualTo(1);
        }
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
                        .content("{\"primaryColor\":\"#AA0000\",\"theme\":\"sombre\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/branding").header("Authorization", bearer("sub-acme", "acme.user", "ACME", "READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value(not("#AA0000")));
    }

    @Test
    void brandingLectureEtModificationParAdmin() throws Exception {
        mvc.perform(put("/api/tenant/branding").with(bff("sub-lecteur", "lecteur", "READER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"#123456\",\"theme\":\"terrain\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/tenant/branding").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"rouge\",\"theme\":\"terrain\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/tenant/branding").with(bff("sub-admin", "admin", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryColor\":\"#123456\",\"theme\":\"terrain\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryColor").value("#123456"))
                .andExpect(jsonPath("$.theme").value("terrain"));
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
    void logoJpegTeleverseEtServi() throws Exception {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0};
        mvc.perform(multipart("/api/tenant/branding/logo")
                        .file(new MockMultipartFile("file", "logo.jpg", "image/jpeg", jpg))
                        .with(bff("sub-admin", "admin", "ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/branding/logo").with(bff("sub-admin", "admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpg));
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
