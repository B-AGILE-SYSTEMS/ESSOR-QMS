package com.essor.qms.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Chaque lien de notification émis par le back ({@code new Alert(..., "/x/" + id, ...)}) doit correspondre à une
 * route de {@code web/src/app/app.routes.ts}. {@code /records/<id>} est résolu par le panneau de notifications.
 */
class NotificationLinksTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path ROUTES = Path.of("../web/src/app/app.routes.ts");
    private static final Pattern ALERT = Pattern.compile("new Alert\\(\\s*AlertKind\\.[A-Z_]+,(.*?);", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("\"(/[a-z0-9/-]+/)\"\\s*\\+");
    private static final Pattern ROUTE = Pattern.compile("path:\\s*'([^']+)'");

    @Test
    void lesLiensDesAlertesPointentVersUneRouteWeb() throws IOException {
        List<String> routes = new ArrayList<>();
        Matcher r = ROUTE.matcher(Files.readString(ROUTES));
        while (r.find()) {
            routes.add("/" + r.group(1));
        }
        List<String> links = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher a = ALERT.matcher(Files.readString(f));
                while (a.find()) {
                    Matcher l = LINK.matcher(a.group(1));
                    while (l.find()) {
                        links.add(l.group(1) + ":id");
                    }
                }
            }
        }
        assertThat(links).contains("/equipements/:id", "/veille/:id");
        assertThat(routes).containsAll(links);
    }
}
