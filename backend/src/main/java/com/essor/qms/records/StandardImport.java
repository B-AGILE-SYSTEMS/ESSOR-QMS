package com.essor.qms.records;

import com.essor.qms.shared.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Lecture des fichiers d'import de référentiel : xlsx (première feuille) ou csv (séparateur {@code ;},
 * guillemets doubles, UTF-8). Colonnes {@code code;titre;texte;parent;auditable}, ligne d'en-tête obligatoire.
 */
final class StandardImport {

    static final List<String> COLUMNS = List.of("code", "titre", "texte", "parent", "auditable");
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] ZIP = {'P', 'K', 3, 4};

    private StandardImport() {
    }

    /** Ligne lue ; {@code line} = numéro de ligne du fichier (en-tête = 1). */
    record ImportLine(int line, String code, String title, String text, String parent, boolean auditable,
                      List<String> errors) {
    }

    record LineError(int line, String message) {
    }

    /**
     * {@code applied} : écrit en base ; faux en simulation ou en cas d'erreur (tout ou rien).
     * {@code created} / {@code updated} : exigences créées / mises à jour (ou qui le seraient).
     */
    record ImportReport(UUID standardId, int created, int updated, List<LineError> errors, boolean applied,
                        boolean dryRun) {
    }

    static List<ImportLine> parse(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Fichier vide");
        }
        boolean xlsx = content.length >= 4 && content[0] == ZIP[0] && content[1] == ZIP[1] && content[2] == ZIP[2]
                && content[3] == ZIP[3];
        List<List<String>> rows = xlsx ? readXlsx(content) : readCsv(content);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Fichier vide");
        }
        List<String> header = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int[] idx = new int[COLUMNS.size()];
        for (int c = 0; c < COLUMNS.size(); c++) {
            idx[c] = header.indexOf(COLUMNS.get(c));
            if (idx[c] < 0 && c < 2) {
                throw ApiException.badRequest("En-tête attendu : " + String.join(";", COLUMNS)
                        + " (colonne « " + COLUMNS.get(c) + " » absente)");
            }
        }
        List<ImportLine> lines = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<String> errors = new ArrayList<>();
            String auditable = cell(row, idx[4]);
            Boolean aud = parseBool(auditable);
            if (aud == null) {
                errors.add("auditable : oui ou non attendu (valeur « " + auditable + " »)");
            }
            lines.add(new ImportLine(r + 1, cell(row, idx[0]), cell(row, idx[1]), emptyToNull(cell(row, idx[2])),
                    emptyToNull(cell(row, idx[3])), aud == null || aud, List.copyOf(errors)));
        }
        return lines;
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? "" : row.get(i).strip();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static Boolean parseBool(String v) {
        return switch (v == null ? "" : v.strip().toLowerCase(Locale.ROOT)) {
            case "", "oui", "o", "true", "vrai", "1", "x", "yes" -> true;
            case "non", "n", "false", "faux", "0", "no" -> false;
            default -> null;
        };
    }

    private static List<List<String>> readXlsx(byte[] content) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            int last = Math.min(sheet.getLastRowNum(), StandardService.MAX_LINES + 1);
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(row.getLastCellNum(), 0) && c < 20; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("Classeur xlsx illisible");
        }
    }

    /** CSV {@code ;} avec guillemets doubles (champ multiligne possible) ; BOM UTF-8 ignoré. */
    static List<List<String>> readCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ';') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Modèle téléchargeable : en-tête et deux lignes d'exemple. */
    static byte[] templateXlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("exigences");
            String[][] data = {
                    COLUMNS.toArray(String[]::new),
                    {"8", "Réalisation des activités opérationnelles", "Chapitre (non audité directement)", "", "non"},
                    {"8.1", "Planification et maîtrise opérationnelles", "Résumé rédigé par l'organisme.", "8", "oui"}};
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            for (int c = 0; c < COLUMNS.size(); c++) {
                sheet.setColumnWidth(c, c == 2 ? 60 * 256 : 20 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] templateCsv() {
        return ("code;titre;texte;parent;auditable\n"
                + "8;Réalisation des activités opérationnelles;Chapitre (non audité directement);;non\n"
                + "8.1;Planification et maîtrise opérationnelles;Résumé rédigé par l'organisme.;8;oui\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
