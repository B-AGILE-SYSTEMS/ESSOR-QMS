package com.essor.qms.regulatory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.essor.qms.ApiExamples;
import com.essor.qms.M4Support;
import com.essor.qms.shared.ExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/** Contrat M4 veille : registre, applicabilité, évaluation → action, taux de conformité, alerte, import, RLS. */
class RegulationIT extends M4Support {

    @Autowired
    RegulationAlerts alerts;
    @Autowired
    TransactionTemplate tx;

    private JsonNode regulation(String ref) throws Exception {
        return call(post("/api/regulations"), QUALITE, Map.of("reference", ref,
                "title", "Code du travail — protection des salariés", "domain", "sst", "jurisdiction", "Maroc",
                "publishedOn", "2004-09-08", "effectiveOn", "2004-06-08", "summary", "Résumé maison.",
                "sourceUrl", "https://www.sgg.gov.ma", "requirements", List.of(
                        Map.of("code", "E1", "text", "Évaluer les risques"),
                        Map.of("code", "E2", "text", "Former les salariés"))), 201);
    }

    private void applicable(JsonNode reg, String site, boolean yes) throws Exception {
        call(put("/api/regulations/" + reg.path("id").asText() + "/applicability"), QUALITE, List.of(Map.of(
                "requirementId", reg.path("requirements").get(0).path("id").asText(), "siteId", site,
                "applicable", yes, "justification", yes ? "Site industriel" : "Sans objet")), 200);
    }

    @Test
    void evaluationNonConformeCreeActionEtTauxDeConformite() throws Exception {
        String site = siteId(QUALITE);
        double before = rate(site);
        JsonNode r1 = regulation(uniq("Loi 65-99 art. 281"));
        JsonNode r2 = regulation(uniq("Décret 2-12-172"));
        JsonNode r3 = regulation(uniq("Loi 11-03"));
        applicable(r1, site, true);
        applicable(r2, site, true);
        applicable(r3, site, false);

        String id1 = r1.path("id").asText();
        JsonNode bad = call(post("/api/regulations/" + id1 + "/evaluations"), QUALITE, Map.of("siteId", site,
                "result", "non_conforme", "evidence", "Visite du 12/11", "comment", "DUER non mis à jour"), 201);
        assertThat(bad.path("action").path("reference").asText()).startsWith("ACT-");
        assertThat(bad.path("nextDue").asText()).isEqualTo(LocalDate.now().plusMonths(12).toString());
        JsonNode action = call(get("/api/records/" + bad.path("action").path("id").asText()), QUALITE, null, 200);
        assertThat(action.path("data").path("regulationId").asText()).isEqualTo(id1);
        ApiExamples.write("evaluation-reglementaire", bad);
        call(post("/api/regulations/" + r2.path("id").asText() + "/evaluations"), QUALITE, Map.of("siteId", site,
                "result", "conforme"), 201);
        call(post("/api/regulations/" + r3.path("id").asText() + "/evaluations"), QUALITE, Map.of("siteId", site,
                "result", "conforme"), 201);
        call(post("/api/regulations/" + id1 + "/evaluations"), QUALITE, Map.of("siteId", site,
                "result", "peut-etre"), 400);
        call(post("/api/regulations/" + id1 + "/evaluations"), LECTEUR, Map.of("siteId", site,
                "result", "conforme"), 403);

        JsonNode comp = call(get("/api/regulations/compliance").param("siteId", site), LECTEUR, null, 200);
        JsonNode s = comp.path("sites").get(0);
        assertThat(s.path("nonConforme").asLong()).isPositive();
        assertThat(s.path("rate").isNumber()).isTrue();
        ApiExamples.write("conformite-reglementaire", comp);
        assertThat(rate(site)).isLessThanOrEqualTo(Math.max(before, 100.0));
        assertThat(call(get("/api/dashboard/summary"), QUALITE, null, 200).path("regulatoryComplianceRate").isNumber())
                .isTrue();
        assertThat(RegulationService.rate(1, 1, 2)).isEqualTo(37.5);

        JsonNode detail = call(get("/api/regulations/" + id1), LECTEUR, null, 200);
        assertThat(detail.path("evaluations").size()).isEqualTo(1);
        assertThat(detail.path("requirements").get(0).path("applicability").get(0).path("applicable").asBoolean())
                .isTrue();
        ApiExamples.write("texte-reglementaire", detail);
        JsonNode list = call(get("/api/regulations").param("siteId", site).param("domain", "sst"), LECTEUR, null, 200);
        assertThat(list).extracting(x -> x.path("id").asText()).contains(id1).doesNotContain(r3.path("id").asText());
        ApiExamples.write("registre-reglementaire", list);
    }

    @Test
    void alerteDeReevaluationEchueSansDoublon() throws Exception {
        String site = siteId(QUALITE);
        JsonNode reg = regulation(uniq("Arrêté 93-08"));
        applicable(reg, site, true);
        call(post("/api/regulations/" + reg.path("id").asText() + "/evaluations"), QUALITE, Map.of("siteId", site,
                "result", "conforme"), 201);
        JsonNode me = call(get("/api/me"), QUALITE, null, 200);
        UUID tenant = UUID.fromString(me.path("tenant").path("id").asText());
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            assertThat(st.executeUpdate("update regulation_evaluation set next_due = current_date - 1 "
                    + "where regulation_id = '" + reg.path("id").asText() + "'")).isEqualTo(1);
        }
        Integer n = ExecutionContext.callAs(null, tenant, () -> tx.execute(t -> alerts.run(LocalDate.now())));
        ExecutionContext.callAs(null, tenant, () -> tx.execute(t -> alerts.run(LocalDate.now())));
        assertThat(n).isPositive();
        JsonNode notifs = call(get("/api/notifications").param("size", "100"), QUALITE, null, 200);
        long count = 0;
        for (JsonNode x : notifs.path("items")) {
            if ("REGULATION_DUE".equals(x.path("type").asText())
                    && x.path("link").asText().endsWith(reg.path("id").asText())) {
                count++;
            }
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    void importToutOuRienEtIsolationTenant() throws Exception {
        String ref = uniq("Loi 28-00");
        String bad = "reference;titre;domaine;juridiction;publication;exigence_code;exigence_texte\n"
                + ref + ";Déchets;environnement;Maroc;2006-12-07;E1;Tri des déchets\n"
                + ";Sans référence;environnement;Maroc;;;\n"
                + ref + ";Déchets;environnement;Maroc;pas-une-date;E2;Registre\n";
        JsonNode rep = call(multipart("/api/regulations/import").file(new MockMultipartFile("file", "r.csv",
                "text/csv", bad.getBytes(StandardCharsets.UTF_8))), QUALITE, null, 200);
        assertThat(rep.path("applied").asBoolean()).isFalse();
        assertThat(rep.path("errors")).extracting(e -> e.path("line").asInt()).containsExactly(3, 4);
        String ok = "reference;titre;domaine;juridiction;exigence_code;exigence_texte\n"
                + ref + ";Déchets;environnement;Maroc;E1;Tri des déchets\n"
                + ref + ";Déchets;environnement;Maroc;E2;Registre des déchets\n";
        JsonNode done = call(multipart("/api/regulations/import").file(new MockMultipartFile("file", "r.csv",
                "text/csv", ok.getBytes(StandardCharsets.UTF_8))), QUALITE, null, 200);
        assertThat(done.path("created").asInt()).isEqualTo(1);
        JsonNode list = call(get("/api/regulations").param("q", ref), QUALITE, null, 200);
        String id = list.get(0).path("id").asText();
        assertThat(call(get("/api/regulations/" + id), QUALITE, null, 200).path("requirements").size()).isEqualTo(2);

        otherTenant();
        call(get("/api/regulations/" + id), AUTRE, null, 404);
        assertThat(call(get("/api/regulations").param("q", ref), AUTRE, null, 200).size()).isZero();
    }

    private double rate(String site) throws Exception {
        JsonNode r = call(get("/api/regulations/compliance").param("siteId", site), LECTEUR, null, 200).path("rate");
        return r.isNumber() ? r.asDouble() : 100.0;
    }
}
