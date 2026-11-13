package com.essor.qms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Garde-fou contre la dérive des mocks : les tests d'intégration écrivent ici des réponses RÉELLES de l'API
 * ({@code docs/api-exemples/<nom>.json}) ; les tests unitaires web et mobile vérifient que leurs fonctions de lecture
 * les comprennent.
 */
public final class ApiExamples {

    /** Depuis {@code backend/} (répertoire de travail de Maven). */
    public static final Path DIR = Path.of("..", "docs", "api-exemples");
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ApiExamples() {
    }

    public static void write(String name, JsonNode response) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(name + ".json"), PRETTY.writeValueAsString(response) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
