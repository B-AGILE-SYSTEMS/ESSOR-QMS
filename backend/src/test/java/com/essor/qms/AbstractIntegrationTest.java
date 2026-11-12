package com.essor.qms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base des tests d'intégration sur un vrai PostgreSQL 16.
 * <ul>
 *   <li>par défaut : Testcontainers ({@code postgres:16.15-alpine}) ;</li>
 *   <li>si {@code ESSOR_TEST_DB_URL} est défini : base PostgreSQL 16 locale (profil de repli quand
 *       Docker n'est pas disponible), avec {@code ESSOR_TEST_DB_OWNER} / {@code ESSOR_TEST_DB_OWNER_PASSWORD}.
 *       La base est remise à zéro au démarrage.</li>
 * </ul>
 * Dans les deux cas l'application se connecte avec {@code essor_app} (non propriétaire) et Flyway avec le propriétaire.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final String APP_PASSWORD = "essor_app_test";

    private static final String URL;
    private static final String OWNER;
    private static final String OWNER_PASSWORD;

    static {
        String localUrl = System.getenv("ESSOR_TEST_DB_URL");
        if (localUrl != null && !localUrl.isBlank()) {
            URL = localUrl;
            OWNER = env("ESSOR_TEST_DB_OWNER", "essor_owner");
            OWNER_PASSWORD = env("ESSOR_TEST_DB_OWNER_PASSWORD", "owner");
            resetLocalDatabase();
        } else {
            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.15-alpine")
                    .withDatabaseName("essor").withUsername("essor_owner").withPassword("owner");
            pg.start();
            URL = pg.getJdbcUrl();
            OWNER = pg.getUsername();
            OWNER_PASSWORD = pg.getPassword();
        }
        createAppRole();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", () -> "essor_app");
        r.add("spring.datasource.password", () -> APP_PASSWORD);
        r.add("spring.flyway.user", () -> OWNER);
        r.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }

    /** Connexion propriétaire, pour préparer des données en contournant la RLS. */
    protected static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(URL, OWNER, OWNER_PASSWORD);
    }

    private static void createAppRole() {
        try (Connection c = ownerConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    DO $$ BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'essor_app') THEN
                        CREATE ROLE essor_app LOGIN PASSWORD '%s';
                      ELSE
                        ALTER ROLE essor_app LOGIN PASSWORD '%s';
                      END IF;
                    END $$""".formatted(APP_PASSWORD, APP_PASSWORD));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void resetLocalDatabase() {
        try (Connection c = ownerConnection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");
            s.execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
