package com.essor.qms.traceability;

import com.essor.qms.shared.ApiException;
import com.essor.qms.shared.AuditLog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Données de base : articles et partenaires (API et import xlsx/csv {@code ;}, en-tête obligatoire, tout ou rien).
 * Articles : {@code code;designation;famille;unite;suivi;dlc}. Partenaires : {@code code;nom;type}.
 * Une ligne dont le code existe met l'élément à jour.
 */
@Service
@Transactional
class MasterDataService {

    static final int MAX_LINES = 5000;
    static final List<String> ARTICLE_COLUMNS = List.of("code", "designation", "famille", "unite", "suivi", "dlc");
    static final List<String> PARTNER_COLUMNS = List.of("code", "nom", "type");

    record Article(UUID id, String code, String label, String family, String unit, String tracking, boolean dlc) {
    }

    record Partner(UUID id, String code, String name, String type) {
    }

    record LineError(int line, String message) {
    }

    record ImportReport(int created, int updated, List<LineError> errors, boolean applied, boolean dryRun) {
    }

    private final JdbcClient jdbc;
    private final TraceSupport support;
    private final AuditLog audit;

    MasterDataService(JdbcClient jdbc, TraceSupport support, AuditLog audit) {
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<Article> articles(String q) {
        return jdbc.sql("""
                select id, code, label, family, unit, tracking, has_dlc from article
                where (:q = '' or code ilike '%' || :q || '%' or label ilike '%' || :q || '%')
                order by code limit 500""").param("q", q == null ? "" : q.strip())
                .query((rs, i) -> new Article(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("label"), rs.getString("family"), rs.getString("unit"),
                        rs.getString("tracking"), rs.getBoolean("has_dlc"))).list();
    }

    @Transactional(readOnly = true)
    List<Partner> partners(String type, String q) {
        return jdbc.sql("""
                select id, code, name, type from partner
                where (:type = '' or type = :type)
                  and (:q = '' or code ilike '%' || :q || '%' or name ilike '%' || :q || '%')
                order by code limit 500""")
                .param("type", type == null ? "" : type).param("q", q == null ? "" : q.strip())
                .query((rs, i) -> new Partner(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("type"))).list();
    }

    Article createArticle(Article a) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        List<String> errors = validateArticle(a);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", errors));
        }
        upsertArticle(a);
        return articles(a.code()).stream().filter(x -> x.code().equals(a.code())).findFirst().orElseThrow();
    }

    Partner createPartner(Partner p) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        List<String> errors = validatePartner(p);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join(" ; ", errors));
        }
        upsertPartner(p);
        return partners(null, p.code()).stream().filter(x -> x.code().equals(p.code())).findFirst().orElseThrow();
    }

    ImportReport importArticles(byte[] content, boolean dryRun) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        List<List<String>> rows = rows(content, ARTICLE_COLUMNS, 2);
        int[] idx = index(rows.get(0), ARTICLE_COLUMNS);
        List<LineError> errors = new ArrayList<>();
        List<Article> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            String dlc = cell(row, idx[5]);
            Boolean hasDlc = parseBool(dlc);
            Article a = new Article(null, cell(row, idx[0]), cell(row, idx[1]), emptyToNull(cell(row, idx[2])),
                    cell(row, idx[3]).isEmpty() ? "u" : cell(row, idx[3]),
                    cell(row, idx[4]).isEmpty() ? "lot" : cell(row, idx[4]).toLowerCase(Locale.ROOT),
                    Boolean.TRUE.equals(hasDlc));
            List<String> errs = new ArrayList<>(validateArticle(a));
            if (hasDlc == null) {
                errs.add("dlc : oui ou non attendu (valeur « " + dlc + " »)");
            }
            if (!seen.add(a.code())) {
                errs.add("code « " + a.code() + " » en double dans le fichier");
            }
            int line = rowLine(r);
            errs.forEach(e -> errors.add(new LineError(line, e)));
            items.add(a);
        }
        return apply(items, errors, dryRun, "article", this::upsertArticle, Article::code);
    }

    ImportReport importPartners(byte[] content, boolean dryRun) {
        support.requireAny("ADMIN", TraceSupport.QUALITY_MANAGER);
        List<List<String>> rows = rows(content, PARTNER_COLUMNS, 3);
        int[] idx = index(rows.get(0), PARTNER_COLUMNS);
        List<LineError> errors = new ArrayList<>();
        List<Partner> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            Partner p = new Partner(null, cell(row, idx[0]), cell(row, idx[1]),
                    cell(row, idx[2]).toLowerCase(Locale.ROOT));
            List<String> errs = new ArrayList<>(validatePartner(p));
            if (!seen.add(p.code())) {
                errs.add("code « " + p.code() + " » en double dans le fichier");
            }
            int line = rowLine(r);
            errs.forEach(e -> errors.add(new LineError(line, e)));
            items.add(p);
        }
        return apply(items, errors, dryRun, "partner", this::upsertPartner, Partner::code);
    }

    private static int rowLine(int r) {
        return r + 1;
    }

    private <T> ImportReport apply(List<T> items, List<LineError> errors, boolean dryRun, String table,
                                   java.util.function.Consumer<T> upsert, java.util.function.Function<T, String> code) {
        Set<String> existing = new HashSet<>(jdbc.sql("select code from " + table).query(String.class).list());
        int updated = (int) items.stream().map(code).filter(existing::contains).count();
        int created = items.size() - updated;
        boolean applied = errors.isEmpty() && !dryRun;
        if (applied) {
            items.forEach(upsert);
            audit.record("MASTER_DATA_IMPORTED", table, null,
                    "{\"created\":" + created + ",\"updated\":" + updated + "}");
        }
        return new ImportReport(created, updated, List.copyOf(errors), applied, dryRun);
    }

    private void upsertArticle(Article a) {
        jdbc.sql("""
                insert into article (tenant_id, code, label, family, unit, tracking, has_dlc)
                values (app_current_tenant(), :code, :label, :family, :unit, :tracking, :dlc)
                on conflict (tenant_id, code) do update set label = excluded.label, family = excluded.family,
                  unit = excluded.unit, tracking = excluded.tracking, has_dlc = excluded.has_dlc, updated_at = now()""")
                .param("code", a.code()).param("label", a.label()).param("family", a.family())
                .param("unit", a.unit() == null || a.unit().isBlank() ? "u" : a.unit())
                .param("tracking", a.tracking() == null ? "lot" : a.tracking()).param("dlc", a.dlc()).update();
    }

    private void upsertPartner(Partner p) {
        jdbc.sql("""
                insert into partner (tenant_id, code, name, type) values (app_current_tenant(), :code, :name, :type)
                on conflict (tenant_id, code) do update set name = excluded.name, type = excluded.type,
                  updated_at = now()""")
                .param("code", p.code()).param("name", p.name()).param("type", p.type()).update();
    }

    private static List<String> validateArticle(Article a) {
        List<String> e = new ArrayList<>();
        if (a.code() == null || a.code().isBlank() || a.code().length() > 50) {
            e.add("code obligatoire (50 caractères maximum)");
        }
        if (a.label() == null || a.label().isBlank() || a.label().length() > 200) {
            e.add("désignation obligatoire (200 caractères maximum)");
        }
        if (a.tracking() != null && !Set.of("lot", "serie").contains(a.tracking())) {
            e.add("suivi : lot ou serie attendu");
        }
        if (a.unit() != null && a.unit().length() > 20) {
            e.add("unité : 20 caractères maximum");
        }
        return e;
    }

    private static List<String> validatePartner(Partner p) {
        List<String> e = new ArrayList<>();
        if (p.code() == null || p.code().isBlank() || p.code().length() > 50) {
            e.add("code obligatoire (50 caractères maximum)");
        }
        if (p.name() == null || p.name().isBlank() || p.name().length() > 200) {
            e.add("nom obligatoire (200 caractères maximum)");
        }
        if (!Set.of("fournisseur", "client").contains(p.type() == null ? "" : p.type())) {
            e.add("type : fournisseur ou client attendu");
        }
        return e;
    }

    // ------------------------------------------------------------------ lecture des fichiers

    private static List<List<String>> rows(byte[] content, List<String> columns, int required) {
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
        for (int c = 0; c < required; c++) {
            if (!header.contains(columns.get(c))) {
                throw ApiException.badRequest("En-tête attendu : " + String.join(";", columns)
                        + " (colonne « " + columns.get(c) + " » absente)");
            }
        }
        return rows;
    }

    private static int[] index(List<String> headerRow, List<String> columns) {
        List<String> header = headerRow.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        return columns.stream().mapToInt(header::indexOf).toArray();
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? "" : row.get(i).strip();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static Boolean parseBool(String v) {
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
                    for (int c = 0; c < Math.max(row.getLastCellNum(), 0) && c < 20; c++) {
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

    Map<String, Object> articleRef(UUID id) {
        return jdbc.sql("select id, code, label, unit, family from article where id = :id").param("id", id)
                .query((rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("code", rs.getString("code"));
                    m.put("label", rs.getString("label"));
                    m.put("unit", rs.getString("unit"));
                    m.put("family", rs.getString("family"));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("Article"));
    }
}
