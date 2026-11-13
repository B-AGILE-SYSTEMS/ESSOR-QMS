package com.essor.qms.shared;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Lecture des fichiers d'import M4 (xlsx ou csv {@code ;} UTF-8, en-tête obligatoire) : même format que les imports
 * M3 / M3b. Rapport commun : {@code {created, updated, errors:[{line,message}], applied, dryRun}}.
 */
public final class TabularFile {

    public static final int MAX_LINES = 5000;
    private static final int MAX_COLUMNS = 30;

    public record LineError(int line, String message) {
    }

    public record ImportReport(int created, int updated, List<LineError> errors, boolean applied, boolean dryRun) {
    }

    /** Lignes lues ; {@link #header()} en minuscules ; numéro de ligne fichier = index + 2. */
    public record Table(List<String> header, List<List<String>> rows) {

        public String cell(List<String> row, String column) {
            int i = header.indexOf(column);
            return i < 0 || i >= row.size() ? "" : row.get(i).strip();
        }
    }

    private TabularFile() {
    }

    /** Vérifie la présence des colonnes {@code required} (les autres de {@code columns} sont facultatives). */
    public static Table read(byte[] content, List<String> columns, List<String> required) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Fichier vide");
        }
        boolean xlsx = content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3
                && content[3] == 4;
        List<List<String>> rows = xlsx ? readXlsx(content) : readCsv(content);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Fichier vide");
        }
        if (rows.size() > MAX_LINES + 1) {
            throw ApiException.badRequest("Fichier trop long (" + MAX_LINES + " lignes maximum)");
        }
        List<String> header = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        for (String c : required) {
            if (!header.contains(c)) {
                throw ApiException.badRequest("En-tête attendu : " + String.join(";", columns)
                        + " (colonne « " + c + " » absente)");
            }
        }
        List<List<String>> body = rows.subList(1, rows.size()).stream()
                .filter(r -> !r.stream().allMatch(String::isBlank)).toList();
        return new Table(header, body);
    }

    public static Boolean parseBool(String v) {
        return switch (v == null ? "" : v.strip().toLowerCase(Locale.ROOT)) {
            case "oui", "o", "true", "vrai", "1", "x", "yes" -> true;
            case "", "non", "n", "false", "faux", "0", "no" -> false;
            default -> null;
        };
    }

    private static List<List<String>> readXlsx(byte[] content) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            int last = Math.min(sheet.getLastRowNum(), MAX_LINES + 1);
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(row.getLastCellNum(), 0) && c < MAX_COLUMNS; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (Exception e) {
            throw ApiException.badRequest("Classeur xlsx illisible");
        }
    }

    /** CSV {@code ;}, guillemets doubles, UTF-8 (BOM ignoré). */
    public static List<List<String>> readCsv(byte[] content) {
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
}
