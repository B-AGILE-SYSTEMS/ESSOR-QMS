package com.essor.qms.risks;

import com.essor.qms.records.Actions.ActionRef;
import com.essor.qms.tenancy.BrandingService;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Document unique d'évaluation des risques professionnels (DUER) d'un site : risques de type {@code sst} par unité de
 * travail, cotations brute et résiduelle, mesures existantes, actions. A4 paysage, logo et couleur du tenant.
 */
@Component
class DuerPdf {

    private final RiskService risks;
    private final BrandingService branding;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    DuerPdf(RiskService risks, BrandingService branding, TransactionTemplate tx, JdbcClient jdbc) {
        this.risks = risks;
        this.branding = branding;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    record Duer(String siteCode, String siteName, List<Map<String, Object>> risks, BrandingService.Branding brand) {
    }

    Duer load(UUID siteId) {
        return tx.execute(s -> {
            Map<String, Object> site = jdbc.sql("select code, name from site where id = :id").param("id", siteId)
                    .query((rs, i) -> Map.<String, Object>of("code", rs.getString(1), "name", rs.getString(2)))
                    .optional().orElseThrow(() -> com.essor.qms.shared.ApiException.notFound("Site"));
            return new Duer((String) site.get("code"), (String) site.get("name"), risks.duerRisks(siteId),
                    branding.get());
        });
    }

    byte[] render(Duer d) {
        Color color = parse(d.brand().primaryColor());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Document unique " + d.siteCode());
            doc.open();
            tx.execute(s -> {
                branding.logo().filter(l -> !"image/svg+xml".equals(l.contentType())).ifPresent(l -> {
                    try {
                        Image img = Image.getInstance(l.content());
                        img.scaleToFit(120, 40);
                        doc.add(img);
                    } catch (Exception e) {
                        // logo illisible : document sans logo
                    }
                });
                return null;
            });
            doc.add(new Paragraph("Document unique d'évaluation des risques professionnels",
                    new Font(Font.HELVETICA, 16, Font.BOLD, color)));
            doc.add(new Paragraph("Site : " + d.siteName() + " (" + d.siteCode() + ") — édité le " + LocalDate.now()
                    + " — " + d.risks().size() + " risque(s)", new Font(Font.HELVETICA, 10)));
            PdfPTable t = new PdfPTable(new float[] {1.1f, 1.4f, 2.4f, 2.2f, 0.9f, 2.4f, 0.9f, 2.2f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(10);
            t.setHeaderRows(1);
            for (String h : List.of("Réf.", "Unité de travail", "Danger", "Situation", "Brut", "Mesures existantes",
                    "Résiduel", "Actions")) {
                PdfPCell c = new PdfPCell(new Phrase(h, new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE)));
                c.setBackgroundColor(color);
                t.addCell(c);
            }
            for (Map<String, Object> r : d.risks()) {
                cell(t, r.get("reference"), null);
                cell(t, r.get("workUnit"), null);
                cell(t, r.get("hazard"), null);
                cell(t, r.get("situation"), null);
                rating(t, r.get("gross"));
                cell(t, r.get("existingMeasures"), null);
                rating(t, r.get("residual"));
                @SuppressWarnings("unchecked")
                List<ActionRef> acts = (List<ActionRef>) r.get("actions");
                cell(t, acts.stream().map(a -> a.reference() + " " + a.title() + " (" + a.status() + ")")
                        .collect(Collectors.joining("\n")), null);
            }
            doc.add(t);
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Génération du document unique impossible", e);
        }
        return out.toByteArray();
    }

    private static void rating(PdfPTable t, Object rated) {
        if (rated == null) {
            cell(t, "—", null);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) rated;
        cell(t, m.get("score") + " " + m.get("levelLabel"), parse((String) m.get("color")));
    }

    private static void cell(PdfPTable t, Object v, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(v == null ? "" : v.toString(), new Font(Font.HELVETICA, 8,
                Font.NORMAL, bg == null ? Color.BLACK : Color.WHITE)));
        if (bg != null) {
            c.setBackgroundColor(bg);
        }
        t.addCell(c);
    }

    private static Color parse(String hex) {
        try {
            return Color.decode(hex);
        } catch (RuntimeException e) {
            return new Color(0x1F, 0x4E, 0x79);
        }
    }
}
