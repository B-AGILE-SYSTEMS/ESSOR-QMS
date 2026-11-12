package com.essor.qms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Connexion BFF réelle contre Keycloak (Testcontainers) qui importe {@code deploy/keycloak/realm-essor.json} :
 * code d'autorisation + PKCE, formulaire de connexion Keycloak, rappel, session {@code SESSION}, {@code GET /api/me}.
 * Profil {@code demo} : vérifie aussi les données de démonstration et le rattachement du compte préparé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"test", "demo"})
class KeycloakLoginIT extends AbstractIntegrationTest {

    static final String IMAGE = System.getProperty("essor.keycloak.image", "quay.io/keycloak/keycloak:26.0.7");
    static final String SECRET = "test-secret";
    static final int APP_PORT = freePort();
    static final String APP = "http://localhost:" + APP_PORT;

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(IMAGE)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HTTP_RELATIVE_PATH", "/auth")
            .withEnv("ESSOR_OIDC_CLIENT_SECRET", SECRET)
            .withEnv("ESSOR_PUBLIC_URL", APP)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/keycloak/realm-essor.json")),
                    "/opt/keycloak/data/import/realm-essor.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/auth/realms/essor/.well-known/openid-configuration").forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    static String realm() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/auth/realms/essor";
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("server.port", () -> APP_PORT);
        r.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> SECRET);
        r.add("spring.security.oauth2.client.provider.keycloak.authorization-uri",
                () -> realm() + "/protocol/openid-connect/auth");
        r.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> realm() + "/protocol/openid-connect/token");
        r.add("spring.security.oauth2.client.provider.keycloak.jwk-set-uri",
                () -> realm() + "/protocol/openid-connect/certs");
        r.add("spring.security.oauth2.client.provider.keycloak.user-info-uri",
                () -> realm() + "/protocol/openid-connect/userinfo");
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> realm() + "/protocol/openid-connect/certs");
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakLoginIT::realm);
    }

    private final ObjectMapper json = new ObjectMapper();
    /**
     * Pot à cookies minimal, comme un navigateur sur localhost : Keycloak 26 pose des cookies {@code Secure} même
     * en HTTP, que {@link java.net.CookieManager} refuse de renvoyer (les navigateurs acceptent localhost).
     */
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        if (!cookies.isEmpty()) {
            req.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        for (String h : r.headers().allValues("set-cookie")) {
            String pair = h.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty() || h.toLowerCase().contains("max-age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return r;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private static String location(HttpResponse<String> r) {
        assertThat(r.statusCode()).as("redirection attendue de " + r.uri()).isBetween(300, 399);
        String loc = r.headers().firstValue("Location").orElseThrow();
        return loc.startsWith("/") ? APP + loc : loc;
    }

    @Test
    void connexionBffCodePkceJusquAApiMe() throws Exception {
        // 1. le BFF redirige vers Keycloak, avec PKCE (S256)
        String authorize = location(get(APP + "/oauth2/authorization/keycloak"));
        assertThat(authorize).startsWith(realm() + "/protocol/openid-connect/auth")
                .contains("client_id=essor-bff").contains("code_challenge=").contains("code_challenge_method=S256");

        // 2. formulaire de connexion Keycloak
        HttpResponse<String> page = get(authorize);
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher action = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"").matcher(page.body());
        if (!action.find()) {
            action = Pattern.compile("action=\"([^\"]+login-actions/authenticate[^\"]*)\"").matcher(page.body());
            assertThat(action.find()).as("formulaire Keycloak").isTrue();
        }
        String formUrl = action.group(1).replace("&amp;", "&");
        String form = "username=" + URLEncoder.encode("admin", StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode("admin", StandardCharsets.UTF_8) + "&credentialId=";
        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(formUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));

        // 3. retour sur le BFF avec le code, échange code + code_verifier, session ouverte
        String callback = location(login);
        assertThat(callback).startsWith(APP + "/login/oauth2/code/keycloak?").contains("code=");
        HttpResponse<String> done = get(callback);
        assertThat(done.statusCode()).isBetween(300, 399);
        assertThat(location(done)).doesNotContain("error");
        assertThat(cookies).containsKey("SESSION");

        // 4. /api/me avec le cookie de session : rôles et tenant issus du jeton Keycloak
        HttpResponse<String> me = get(APP + "/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(me.body());
        assertThat(body.path("username").asText()).isEqualTo("admin");
        assertThat(body.path("tenant").path("code").asText()).isEqualTo("DEMO");
        assertThat(body.path("roles").toString()).contains("ADMIN").contains("QUALITY_MANAGER");
        // compte de démo préparé rattaché : sites de démo visibles
        assertThat(body.path("sites").findValuesAsText("code")).contains("SIEGE", "USINE-CASA", "ENTREPOT-TNG");

        // 5. données de démo : 6 NC à des statuts différents, avec leurs actions
        java.util.List<String> statuses = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            HttpResponse<String> r = get(APP + "/api/records/00000000-0000-0000-0000-00000000c00" + i);
            assertThat(r.statusCode()).isEqualTo(200);
            statuses.add(json.readTree(r.body()).path("status").asText());
        }
        assertThat(statuses).containsExactly("DECLAREE", "QUALIFIEE", "ANALYSE", "ACTIONS", "VERIFICATION",
                "CLOTUREE");
        JsonNode nc4 = json.readTree(get(APP + "/api/records/00000000-0000-0000-0000-00000000c004").body());
        assertThat(nc4.path("children").size()).isEqualTo(2);
        JsonNode tasks = json.readTree(get(APP + "/api/my-tasks").body());
        assertThat(tasks.findValuesAsText("id")).contains("00000000-0000-0000-0000-00000000c004");

        // sans session : 401
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(APP + "/api/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
