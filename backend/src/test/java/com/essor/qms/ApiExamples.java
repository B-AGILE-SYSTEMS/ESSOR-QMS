package com.essor.qms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Garde-fou contre la dérive des mocks : les tests d'intégration produisent des réponses RÉELLES de l'API.
 * <ul>
 *   <li>chaque réponse est écrite dans {@code target/api-exemples/<nom>.json} (jamais dans {@code docs/}) ;</li>
 *   <li>sa STRUCTURE (clés et types, pas les valeurs) est comparée à {@code docs/api-exemples/<nom>.json} : une
 *       différence fait échouer le test (le web et le mobile lisent ces fichiers) ;</li>
 *   <li>régénération volontaire de {@code docs/api-exemples/} : {@code mvn -B -Papi-exemples verify}
 *       (propriété {@code essor.api-exemples.update=true}).</li>
 * </ul>
 */
public final class ApiExamples {

    /** Depuis {@code backend/} (répertoire de travail de Maven). */
    public static final Path DOCS = Path.of("..", "docs", "api-exemples");
    public static final Path TARGET = Path.of("target", "api-exemples");
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ApiExamples() {
    }

    public static void write(String name, JsonNode response) {
        try {
            String text = PRETTY.writeValueAsString(response) + "\n";
            Files.createDirectories(TARGET);
            Files.writeString(TARGET.resolve(name + ".json"), text);
            Path doc = DOCS.resolve(name + ".json");
            if (Boolean.getBoolean("essor.api-exemples.update")) {
                Files.createDirectories(DOCS);
                Files.writeString(doc, text);
                return;
            }
            if (!Files.exists(doc)) {
                throw new AssertionError("Exemple docs/api-exemples/" + name + ".json absent : le générer avec "
                        + "mvn -B -Papi-exemples verify");
            }
            List<String> diff = compare(PRETTY.readTree(Files.readString(doc)), response);
            if (!diff.isEmpty()) {
                throw new AssertionError("Structure de docs/api-exemples/" + name + ".json différente de la réponse "
                        + "réelle (régénérer avec mvn -B -Papi-exemples verify) :\n  " + String.join("\n  ", diff));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Différences de structure : mêmes clés d'objet, mêmes types (objet, tableau, texte, nombre, booléen). Une valeur
     * nulle d'un côté est compatible avec tout type ; un tableau vide aussi. Les éléments d'un tableau sont comparés
     * deux à deux (structure du premier élément de référence contre chaque élément réel).
     */
    public static List<String> compare(JsonNode expected, JsonNode actual) {
        List<String> out = new ArrayList<>();
        compare("$", expected, actual, out);
        return out;
    }

    private static void compare(String path, JsonNode e, JsonNode a, List<String> out) {
        if (e == null || a == null || e.isNull() || a.isNull()) {
            return;
        }
        String te = type(e);
        String ta = type(a);
        if (!te.equals(ta)) {
            out.add(path + " : " + te + " attendu, " + ta + " reçu");
            return;
        }
        if (e.isObject()) {
            TreeSet<String> ke = names(e);
            TreeSet<String> ka = names(a);
            for (String k : ke) {
                if (!ka.contains(k)) {
                    out.add(path + "." + k + " : clé absente de la réponse");
                } else {
                    compare(path + "." + k, e.get(k), a.get(k), out);
                }
            }
            for (String k : ka) {
                if (!ke.contains(k)) {
                    out.add(path + "." + k + " : clé nouvelle, absente de l'exemple");
                }
            }
        } else if (e.isArray() && !e.isEmpty()) {
            for (int i = 0; i < a.size(); i++) {
                compare(path + "[" + i + "]", e.get(Math.min(i, e.size() - 1)), a.get(i), out);
            }
        }
    }

    private static TreeSet<String> names(JsonNode n) {
        TreeSet<String> s = new TreeSet<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            s.add(it.next());
        }
        return s;
    }

    private static String type(JsonNode n) {
        if (n.isObject()) {
            return "objet";
        }
        if (n.isArray()) {
            return "tableau";
        }
        if (n.isNumber()) {
            return "nombre";
        }
        if (n.isBoolean()) {
            return "booléen";
        }
        return "texte";
    }
}
