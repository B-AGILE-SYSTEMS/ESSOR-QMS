package com.essor.qms.records;

import com.essor.qms.records.AuditService.AuditDetail;
import com.essor.qms.records.AuditService.ChapterRate;
import com.essor.qms.records.AuditService.NcRef;
import com.essor.qms.records.AuditService.Question;
import com.essor.qms.records.AuditService.Summary;
import com.essor.qms.tenancy.BrandingService;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Rapport d'audit PDF généré côté serveur (OpenPDF) : logo et couleur du tenant, synthèse, chapitres, constats, NC. */
@Component
class AuditReportPdf {

    private static final Map<String, String> RESULT_LABELS = Map.of(
            "conforme", "Conforme", "ecart_mineur", "Écart mineur", "ecart_majeur", "Écart majeur",
            "observation", "Observation", "na", "Non applicable");

    private final AuditService audits;
    private final BrandingService branding;
    private final TransactionTemplate tx;

    AuditReportPdf(AuditService audits, BrandingService branding, TransactionTemplate tx) {
        this.audits = audits;
        this.branding = branding;
        this.tx = tx;
    }

    byte[] render(UUID auditId) {
        AuditDetail a = tx.execute(s -> audits.detail(auditId));
        BrandingService.Branding brand = branding.get();
        Color color = parseColor(brand.primaryColor());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Rapport d'audit " + a.reference());
            doc.open();
            header(doc, a, color);
            synthesis(doc, a.summary(), color);
            chapters(doc, a.summary().chapters(), color);
            findings(doc, a.questions(), color);
            ncs(doc, a.ncs(), color);
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Génération du rapport impossible", e);
        }
        return out.toByteArray();
    }

    private void header(Document doc, AuditDetail a, Color color) throws DocumentException {
        branding.logo().filter(l -> !"image/svg+xml".equals(l.contentType())).ifPresent(l -> {
            try {
                Image img = Image.getInstance(l.content());
                img.scaleToFit(140, 50);
                doc.add(img);
            } catch (Exception e) {
                // logo illisible : rapport sans logo
            }
        });
        doc.add(new Paragraph("Rapport d'audit " + a.reference(), font(18, Font.BOLD, color)));
        doc.add(new Paragraph(a.title(), font(12, Font.BOLD, Color.BLACK)));
        Map<String, Object> d = a.data();
        PdfPTable t = table(2, new float[] {1, 3});
        row(t, "Site", a.siteName());
        row(t, "Statut", a.statusLabel());
        row(t, "Date prévue", str(d.get("datePrevue")));
        row(t, "Périmètre", str(d.get("perimetre")));
        row(t, "Auditeurs", str(d.get("auditeurs")));
        row(t, "Audités", str(d.get("audites")));
        t.setSpacingBefore(8);
        doc.add(t);
    }

    private void synthesis(Document doc, Summary s, Color color) throws DocumentException {
        doc.add(section("Synthèse", color));
        PdfPTable t = table(2, new float[] {2, 1});
        row(t, "Taux de conformité", s.conformityRate() == null ? "—" : s.conformityRate() + " %");
        row(t, "Questions répondues", s.answered() + " / " + s.questions());
        row(t, "Conformes", String.valueOf(s.conforme()));
        row(t, "Écarts majeurs", String.valueOf(s.ecartMajeur()));
        row(t, "Écarts mineurs", String.valueOf(s.ecartMineur()));
        row(t, "Observations", String.valueOf(s.observation()));
        row(t, "Non applicables", String.valueOf(s.na()));
        doc.add(t);
    }

    private void chapters(Document doc, List<ChapterRate> chapters, Color color) throws DocumentException {
        doc.add(section("Taux de conformité par chapitre", color));
        PdfPTable t = table(4, new float[] {3, 1, 1, 1});
        headerRow(t, color, "Chapitre", "Évalués", "Écarts", "Conformité");
        for (ChapterRate c : chapters) {
            cells(t, c.chapter() + " " + nz(c.title()), String.valueOf(c.answered()), String.valueOf(c.deviations()),
                    c.conformityRate() == null ? "—" : c.conformityRate() + " %");
        }
        doc.add(t);
    }

    private void findings(Document doc, List<Question> questions, Color color) throws DocumentException {
        doc.add(section("Constats", color));
        PdfPTable t = table(3, new float[] {3, 1.2f, 3});
        headerRow(t, color, "Question", "Résultat", "Commentaire et preuve");
        for (Question q : questions) {
            String comment = nz(q.comment()) + (q.evidence() == null ? "" : "\nPreuve : " + q.evidence());
            cells(t, q.text(), q.result() == null ? "Non répondu" : RESULT_LABELS.get(q.result()), comment);
        }
        doc.add(t);
    }

    private void ncs(Document doc, List<NcRef> ncs, Color color) throws DocumentException {
        doc.add(section("Non-conformités créées", color));
        if (ncs.isEmpty()) {
            doc.add(new Paragraph("Aucune.", font(10, Font.NORMAL, Color.BLACK)));
            return;
        }
        PdfPTable t = table(3, new float[] {1.3f, 4, 1});
        headerRow(t, color, "Référence", "Titre", "Gravité");
        for (NcRef n : ncs) {
            cells(t, n.reference(), n.title(), nz(n.severity()));
        }
        doc.add(t);
    }

    private static Paragraph section(String title, Color color) {
        Paragraph p = new Paragraph(title, font(13, Font.BOLD, color));
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        return p;
    }

    private static PdfPTable table(int columns, float[] widths) throws DocumentException {
        PdfPTable t = new PdfPTable(columns);
        t.setWidthPercentage(100);
        t.setWidths(widths);
        return t;
    }

    private static void row(PdfPTable t, String label, String value) {
        t.addCell(new Phrase(label, font(10, Font.BOLD, Color.DARK_GRAY)));
        t.addCell(new Phrase(nz(value), font(10, Font.NORMAL, Color.BLACK)));
    }

    private static void headerRow(PdfPTable t, Color color, String... labels) {
        for (String l : labels) {
            PdfPCell c = new PdfPCell(new Phrase(l, font(10, Font.BOLD, Color.WHITE)));
            c.setBackgroundColor(color);
            c.setHorizontalAlignment(Element.ALIGN_LEFT);
            t.addCell(c);
        }
        t.setHeaderRows(1);
    }

    private static void cells(PdfPTable t, String... values) {
        for (String v : values) {
            t.addCell(new Phrase(nz(v), font(9, Font.NORMAL, Color.BLACK)));
        }
    }

    private static Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    static Color parseColor(String hex) {
        try {
            return hex != null && hex.matches("^#[0-9A-Fa-f]{6}$") ? Color.decode(hex) : new Color(0x1F4E79);
        } catch (NumberFormatException e) {
            return new Color(0x1F4E79);
        }
    }

    private static String str(Object v) {
        if (v instanceof List<?> l) {
            return String.join(", ", l.stream().map(String::valueOf).toList());
        }
        return v == null ? "" : v.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

}
