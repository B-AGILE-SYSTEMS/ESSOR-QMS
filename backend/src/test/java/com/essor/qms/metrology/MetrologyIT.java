package com.essor.qms.metrology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.essor.qms.ApiExamples;
import com.essor.qms.M4Support;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Contrat M4 métrologie : verdict EMT serveur, blocage + NC + notification, effet instrument, 409 au contrôle. */
class MetrologyIT extends M4Support {

    private JsonNode instrument(String code, String site, int periodMonths) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "label", "Pied à coulisse 150 mm",
                "type", "pied_a_coulisse", "siteId", site, "location", "Labo réception", "serialNumber", "SN-" + code,
                "rangeMin", 0, "rangeMax", 150, "unit", "mm", "resolution", 0.01));
        body.put("emt", 0.03);
        body.put("periodMonths", periodMonths);
        body.put("provider", "Labo Métrologie Casablanca");
        return call(post("/api/instruments"), QUALITE, body, 201);
    }

    private JsonNode calibrate(String id, LocalDate on, double... mesures) throws Exception {
        double[] nominals = {10, 50, 100};
        List<Map<String, Object>> pts = new java.util.ArrayList<>();
        for (int i = 0; i < mesures.length; i++) {
            pts.add(Map.of("nominal", nominals[i], "mesure", mesures[i]));
        }
        return call(post("/api/instruments/" + id + "/calibrations"), TERRAIN, Map.of("calibratedOn", on.toString(),
                "certificateNumber", "CE-" + on, "points", pts, "comment", "Étalonnage annuel"), 201);
    }

    @Test
    void etalonnageConformeVerdictServeurEtEcheance() throws Exception {
        String site = siteId(QUALITE);
        JsonNode inst = instrument(uniq("PAC"), site, 12);
        assertThat(inst.path("qr").asText()).isEqualTo("essor://instrument/" + inst.path("id").asText());
        assertThat(inst.path("status").asText()).isEqualTo("en_service");
        String id = inst.path("id").asText();
        LocalDate on = LocalDate.now().minusDays(10);
        JsonNode cal = calibrate(id, on, 10.01, 50.02, 99.97);
        assertThat(cal.path("verdict").asText()).isEqualTo("conforme");
        assertThat(cal.path("maxAbsError").decimalValue()).isEqualByComparingTo("0.03");
        assertThat(cal.path("points").get(2).path("ecart").decimalValue()).isEqualByComparingTo("-0.03");
        assertThat(cal.path("nextDue").asText()).isEqualTo(on.plusMonths(12).toString());

        MockMultipartFile pdf = new MockMultipartFile("file", "certificat.pdf", "application/pdf",
                "%PDF-1.4\n%essor\n".getBytes(StandardCharsets.US_ASCII));
        JsonNode withCert = call(multipart("/api/calibrations/" + cal.path("id").asText() + "/certificate").file(pdf),
                TERRAIN, null, 200);
        assertThat(withCert.path("certificate").path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(perform(get("/api/calibrations/" + cal.path("id").asText() + "/certificate"), LECTEUR, null, 200)
                .getResponse().getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        ApiExamples.write("etalonnage", withCert);

        JsonNode detail = call(get("/api/instruments/" + id), LECTEUR, null, 200);
        assertThat(detail.path("instrument").path("nextDue").asText()).isEqualTo(on.plusMonths(12).toString());
        assertThat(detail.path("instrument").path("dueState").asText()).isEqualTo("ok");
        assertThat(detail.path("calibrations").size()).isEqualTo(1);
        ApiExamples.write("instrument", detail);
        call(post("/api/instruments"), TERRAIN, Map.of("code", "X", "label", "X", "emt", 1, "periodMonths", 12), 403);
        call(post("/api/instruments/" + id + "/calibrations"), TERRAIN, Map.of("calibratedOn",
                LocalDate.now().toString(), "points", List.of()), 400);
    }

    @Test
    void horsToleranceBloqueCreeNcNotifieEtEffetInstrumentMetEnQuarantaine() throws Exception {
        String site = siteId(TERRAIN);
        siteId(QUALITE);
        JsonNode inst = instrument(uniq("PAC"), site, 12);
        String id = inst.path("id").asText();
        calibrate(id, LocalDate.now().minusDays(30), 10.0, 50.0, 100.0);

        // contrôle mesuré avec l'instrument (lien, plus le champ libre)
        JsonNode art = article(uniq("ART"));
        JsonNode sup = partner(uniq("FOU"), "fournisseur");
        JsonNode lot = call(post("/api/lots"), TERRAIN, Map.of("articleId", art.path("id").asText(), "numero",
                uniq("L"), "supplierId", sup.path("id").asText(), "quantity", 500, "siteId", site), 201);
        JsonNode plan = plan(art);
        JsonNode insp = call(post("/api/inspections"), TERRAIN, Map.of("lotId", lot.path("id").asText(),
                "controlPlanId", plan.path("id").asText(), "point", "RECEPTION"), 201);
        String inspId = insp.path("id").asText();
        JsonNode res = call(put("/api/inspections/" + inspId + "/results"), TERRAIN, List.of(Map.of("characteristic",
                "DIAM", "values", List.of(10.0, 10.1), "instrumentId", id)), 200);
        assertThat(res.path("results").get(0).path("instrumentId").asText()).isEqualTo(id);
        assertThat(res.path("results").get(0).path("instrument").asText()).isEqualTo(inst.path("code").asText());

        // étalonnage non conforme : blocage, NC, notification
        JsonNode bad = calibrate(id, LocalDate.now(), 10.0, 50.05, 100.0);
        assertThat(bad.path("verdict").asText()).isEqualTo("non_conforme");
        assertThat(bad.path("points").get(1).path("conforme").asBoolean()).isFalse();
        assertThat(bad.path("ncReference").asText()).startsWith("NC-");
        JsonNode nc = call(get("/api/records/" + bad.path("ncId").asText()), QUALITE, null, 200);
        assertThat(nc.path("data").path("instrumentId").asText()).isEqualTo(id);
        assertThat(call(get("/api/instruments/" + id), QUALITE, null, 200).path("instrument").path("status").asText())
                .isEqualTo("bloque");
        JsonNode notifs = call(get("/api/notifications"), QUALITE, null, 200);
        assertThat(notifs.path("items")).anySatisfy(n -> {
            assertThat(n.path("type").asText()).isEqualTo("INSTRUMENT_BLOCKED");
            assertThat(n.path("link").asText()).isEqualTo("/ressources/instruments/" + id);
        });

        // 409 : instrument bloqué non sélectionnable
        JsonNode err = call(put("/api/inspections/" + inspId + "/results"), TERRAIN, List.of(Map.of(
                "characteristic", "DIAM", "values", List.of(10.0), "instrumentId", id)), 409);
        assertThat(err.path("error").asText()).isEqualTo("instrument_unavailable");
        assertThat(call(get("/api/instruments").param("selectable", "true").param("q", inst.path("code").asText()),
                TERRAIN, null, 200).size()).isZero();

        // effet instrument depuis le dernier étalonnage conforme
        JsonNode impact = call(get("/api/instruments/" + id + "/impact"), QUALITE, null, 200);
        assertThat(impact.path("since").asText()).isEqualTo(LocalDate.now().minusDays(30).toString());
        assertThat(impact.path("inspections")).extracting(i -> i.path("id").asText()).containsExactly(inspId);
        assertThat(impact.path("lots")).extracting(l -> l.path("id").asText())
                .containsExactly(lot.path("id").asText());
        ApiExamples.write("effet-instrument", impact);
        assertThat(call(get("/api/instruments/" + id + "/impact").param("since",
                LocalDate.now().plusDays(1).toString()), QUALITE, null, 200).path("lots").size()).isZero();

        call(post("/api/instruments/" + id + "/impact/quarantine"), TERRAIN, Map.of(), 403);
        JsonNode q = call(post("/api/instruments/" + id + "/impact/quarantine"), QUALITE,
                Map.of("reason", "Réévaluation des lots mesurés"), 200);
        assertThat(q.path("quarantined")).extracting(l -> l.path("id").asText())
                .containsExactly(lot.path("id").asText());
        ApiExamples.write("quarantaine-instrument", q);
        JsonNode lotDetail = call(get("/api/lots/" + lot.path("id").asText()), QUALITE, null, 200);
        assertThat(lotDetail.path("lot").path("qualityStatus").asText()).isEqualTo("QUARANTAINE");
        assertThat(lotDetail.path("history").get(lotDetail.path("history").size() - 1).path("ncId").asText())
                .isEqualTo(bad.path("ncId").asText());

        // un nouvel étalonnage conforme remet en service
        assertThat(calibrate(id, LocalDate.now(), 10.0, 50.0, 100.0).path("verdict").asText()).isEqualTo("conforme");
        assertThat(call(get("/api/instruments/" + id), QUALITE, null, 200).path("instrument").path("status").asText())
                .isEqualTo("en_service");
    }

    @Test
    void instrumentEchuRefuseEtKpiTableauDeBord() throws Exception {
        String site = siteId(QUALITE);
        Map<String, Object> body = new HashMap<>(Map.of("code", uniq("ECH"), "label", "Balance", "emt", 0.5,
                "periodMonths", 6, "siteId", site, "nextDue", LocalDate.now().minusDays(1).toString()));
        JsonNode echu = call(post("/api/instruments"), QUALITE, body, 201);
        assertThat(echu.path("dueState").asText()).isEqualTo("echu");
        assertThat(echu.path("selectable").asBoolean()).isFalse();
        body.put("code", uniq("BIE"));
        body.put("nextDue", LocalDate.now().plusDays(10).toString());
        assertThat(call(post("/api/instruments"), QUALITE, body, 201).path("dueState").asText()).isEqualTo("bientot");

        JsonNode art = article(uniq("ART"));
        JsonNode lot = call(post("/api/lots"), TERRAIN, Map.of("articleId", art.path("id").asText(), "numero",
                uniq("L"), "quantity", 10, "siteId", site), 201);
        JsonNode insp = call(post("/api/inspections"), TERRAIN, Map.of("lotId", lot.path("id").asText(),
                "controlPlanId", plan(art).path("id").asText(), "point", "RECEPTION"), 201);
        JsonNode err = call(put("/api/inspections/" + insp.path("id").asText() + "/results"), TERRAIN,
                List.of(Map.of("characteristic", "DIAM", "values", List.of(10.0), "instrumentId",
                        echu.path("id").asText())), 409);
        assertThat(err.path("details").path("overdue").asBoolean()).isTrue();

        JsonNode dash = call(get("/api/dashboard/summary"), QUALITE, null, 200);
        assertThat(dash.path("calibrationsDueSoon").asLong()).isPositive();
        assertThat(dash.path("instrumentsOverdue").asLong()).isPositive();
    }

    @Test
    void importInstrumentsToutOuRienEtIsolationTenant() throws Exception {
        String code = uniq("IMP");
        String csv = "code;designation;type;site;emt;periodicite_mois;prochaine_echeance\n"
                + code + ";Thermomètre;thermometre;;0,5;12;2027-01-31\n"
                + ";Sans code;;;abc;0;\n";
        MockMultipartFile f = new MockMultipartFile("file", "i.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        JsonNode rep = call(multipart("/api/instruments/import").file(f), QUALITE, null, 200);
        assertThat(rep.path("applied").asBoolean()).isFalse();
        assertThat(rep.path("errors")).allSatisfy(e -> assertThat(e.path("line").asInt()).isEqualTo(3));
        String ok = "code;designation;emt;periodicite_mois\n" + code + ";Thermomètre;0.5;12\n";
        JsonNode done = call(multipart("/api/instruments/import").file(new MockMultipartFile("file", "i.csv",
                "text/csv", ok.getBytes(StandardCharsets.UTF_8))), QUALITE, null, 200);
        assertThat(done.path("applied").asBoolean()).isTrue();
        assertThat(done.path("created").asInt()).isEqualTo(1);
        JsonNode list = call(get("/api/instruments").param("q", code), QUALITE, null, 200);
        assertThat(list.size()).isEqualTo(1);

        otherTenant();
        assertThat(call(get("/api/instruments").param("q", code), AUTRE, null, 200).size()).isZero();
        call(get("/api/instruments/" + list.get(0).path("id").asText()), AUTRE, null, 404);
    }
}
