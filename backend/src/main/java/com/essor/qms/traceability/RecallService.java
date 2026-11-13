package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import com.essor.qms.tenancy.BrandingService;
import com.essor.qms.traceability.Genealogy.ClientImpact;
import com.essor.qms.traceability.Genealogy.ImpactedLot;
import com.essor.qms.traceability.Genealogy.Recall;
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
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Traçabilité ascendante / descendante, simulation de rappel, blocage du stock restant, dossier PDF. */
@Service
@Transactional
class RecallService {

    static final int DEFAULT_DEPTH = 10;
    static final int MAX_DEPTH = 50;
    private static final Set<String> NOT_BLOCKABLE = Set.of("QUARANTAINE", "REFUSE", "DETRUIT");
    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");

    record BlockResult(List<LotService.Lot> blocked) {
    }

    private final JdbcClient jdbc;
    private final LotService lots;
    private final TraceSupport support;
    private final BrandingService branding;

    RecallService(JdbcClient jdbc, LotService lots, TraceSupport support, BrandingService branding) {
        this.jdbc = jdbc;
        this.lots = lots;
        this.support = support;
        this.branding = branding;
    }

    @Transactional(readOnly = true)
    Genealogy.Node trace(UUID id, String direction, Integer depth) {
        lots.lot(id);
        boolean down = switch (direction == null ? "down" : direction) {
            case "down" -> true;
            case "up" -> false;
            default -> throw ApiException.badRequest("direction : up ou down");
        };
        int d = depth == null ? DEFAULT_DEPTH : Math.max(1, Math.min(depth, MAX_DEPTH));
        return Genealogy.tree(jdbc, id, down, d);
    }

    @Transactional(readOnly = true)
    Recall recall(UUID id) {
        lots.lot(id);
        return Genealogy.recall(jdbc, id);
    }

    /** Met en QUARANTAINE les lots impactés qui ont encore du stock (restant > 0) ; chaque blocage part en webhook. */
    BlockResult block(UUID id, String reason) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        String r = LotService.blankToNull(reason);
        if (r == null) {
            throw ApiException.badRequest("Motif du blocage obligatoire");
        }
        Recall recall = recall(id);
        List<LotService.Lot> blocked = new ArrayList<>();
        for (ImpactedLot l : recall.lotsImpactes()) {
            if (l.remaining().signum() > 0 && !NOT_BLOCKABLE.contains(l.qualityStatus())) {
                lots.applyStatus(l.id(), "QUARANTAINE", "Rappel lot " + recall.source().numero() + " : " + r, null,
                        null);
                blocked.add(lots.lot(l.id()));
            }
        }
        return new BlockResult(List.copyOf(blocked));
    }

    String fileName(UUID id) {
        return "rappel-" + lots.lot(id).numero().replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf";
    }

    @Transactional(readOnly = true)
    byte[] pdf(UUID id) {
        Recall recall = recall(id);
        BrandingService.Branding brand = branding.get();
        Color color = parseColor(brand.primaryColor());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Dossier de rappel " + recall.source().numero());
            doc.open();
            branding.logo().filter(l -> !"image/svg+xml".equals(l.contentType())).ifPresent(l -> {
                try {
                    Image img = Image.getInstance(l.content());
                    img.scaleToFit(140, 50);
                    doc.add(img);
                } catch (Exception e) {
                    // logo illisible : dossier sans logo
                }
            });
            LotService.Lot s = recall.source();
            doc.add(new Paragraph("Dossier de rappel — lot " + s.numero(), font(18, Font.BOLD, color)));
            doc.add(new Paragraph("Édité le " + ZonedDateTime.now(ZONE)
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), font(9, Font.NORMAL, Color.DARK_GRAY)));
            PdfPTable t = table(2, new float[] {1, 3});
            row(t, "Article", s.article().code() + " — " + s.article().label());
            row(t, "Fournisseur", s.supplier() == null ? "" : s.supplier().code() + " — " + s.supplier().name());
            row(t, "Lot fournisseur", s.supplierLot());
            row(t, "Statut qualité", s.qualityStatus());
            row(t, "Quantité", fmt(s.quantity()) + " " + s.unit());
            t.setSpacingBefore(8);
            doc.add(t);

            doc.add(section("Quantités impactées", color));
            PdfPTable q = table(2, new float[] {2, 1});
            row(q, "En stock", fmt(recall.quantites().enStock()));
            row(q, "En cours", fmt(recall.quantites().enCours()));
            row(q, "Expédiées", fmt(recall.quantites().expedie()));
            doc.add(q);

            doc.add(section("Lots impactés (" + recall.lotsImpactes().size() + ")", color));
            PdfPTable lt = table(5, new float[] {2, 3, 1.5f, 1.2f, 1.2f});
            header(lt, color, "Lot", "Article", "Statut", "Quantité", "Restant");
            for (ImpactedLot l : recall.lotsImpactes()) {
                cells(lt, l.numero(), l.article().get("code") + " " + l.article().get("label"), l.qualityStatus(),
                        fmt(l.quantity()) + " " + l.unit(), fmt(l.remaining()));
            }
            doc.add(lt);

            doc.add(section("Clients livrés (" + recall.clients().size() + ")", color));
            if (recall.clients().isEmpty()) {
                doc.add(new Paragraph("Aucun.", font(10, Font.NORMAL, Color.BLACK)));
            } else {
                PdfPTable ct = table(3, new float[] {3, 1, 3});
                header(ct, color, "Client", "Quantité", "Références (BL, SSCC)");
                for (ClientImpact c : recall.clients()) {
                    cells(ct, c.partner().code() + " — " + c.partner().name(), fmt(c.quantite()),
                            String.join(", ", c.references()));
                }
                doc.add(ct);
            }
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Génération du dossier impossible", e);
        }
        return out.toByteArray();
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
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
        t.addCell(new Phrase(value == null ? "" : value, font(10, Font.NORMAL, Color.BLACK)));
    }

    private static void header(PdfPTable t, Color color, String... labels) {
        for (String l : labels) {
            PdfPCell c = new PdfPCell(new Phrase(l, font(10, Font.BOLD, Color.WHITE)));
            c.setBackgroundColor(color);
            t.addCell(c);
        }
        t.setHeaderRows(1);
    }

    private static void cells(PdfPTable t, String... values) {
        for (String v : values) {
            t.addCell(new Phrase(v == null ? "" : v, font(9, Font.NORMAL, Color.BLACK)));
        }
    }

    private static Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    private static Color parseColor(String hex) {
        return hex != null && hex.matches("^#[0-9A-Fa-f]{6}$") ? Color.decode(hex) : new Color(0x1F4E79);
    }
}
