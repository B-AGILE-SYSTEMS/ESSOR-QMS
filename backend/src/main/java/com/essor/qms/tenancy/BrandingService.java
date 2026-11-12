package com.essor.qms.tenancy;

import com.essor.qms.shared.AuditLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Habillage du tenant courant : couleur, thème, logo (stocké sur disque local). */
@Service
public class BrandingService {

    public static final long MAX_LOGO_BYTES = 512 * 1024;
    static final String LOGO_URL = "/api/tenant/branding/logo";

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final Pattern SVG_FORBIDDEN = Pattern.compile(
            "<script|javascript:|<foreignObject|\\son[a-z]+\\s*=|<!ENTITY", Pattern.CASE_INSENSITIVE);

    public record Branding(String logoUrl, String primaryColor, String theme) {
    }

    public record Logo(byte[] content, String contentType) {
    }

    public static class InvalidLogoException extends RuntimeException {
        InvalidLogoException(String message) {
            super(message);
        }
    }

    private record Row(String primaryColor, String theme, String logoFile, String logoContentType) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final Path storageRoot;

    BrandingService(JdbcClient jdbc, TransactionTemplate tx, AuditLog audit,
                    @Value("${essor.storage.dir}") Path storageRoot) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.storageRoot = storageRoot;
    }

    public Branding get() {
        Row row = tx.execute(s -> currentRow());
        return new Branding(row.logoFile() == null ? null : LOGO_URL, row.primaryColor(), row.theme());
    }

    public Branding update(String primaryColor, String theme) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    update tenant_branding set primary_color = :color, theme = :theme, updated_at = now()
                    where tenant_id = app_current_tenant()
                    """).param("color", primaryColor).param("theme", theme).update();
            audit.record("BRANDING_UPDATED", "tenant_branding", null, null);
        });
        return get();
    }

    public Branding storeLogo(byte[] content) {
        String contentType = detectType(content);
        String ext = contentType.equals("image/png") ? "png" : "svg";
        tx.executeWithoutResult(s -> {
            UUID tenantId = jdbc.sql("select app_current_tenant()").query(UUID.class).single();
            Path dir = storageRoot.resolve("branding").resolve(tenantId.toString());
            String fileName = "logo." + ext;
            try {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "upload", ".tmp");
                Files.write(tmp, content);
                Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc.sql("""
                    update tenant_branding set logo_file = :file, logo_content_type = :type, updated_at = now()
                    where tenant_id = app_current_tenant()
                    """).param("file", tenantId + "/" + fileName).param("type", contentType).update();
            audit.record("BRANDING_LOGO_UPDATED", "tenant_branding", null,
                    "{\"contentType\":\"" + contentType + "\",\"size\":" + content.length + "}");
        });
        return get();
    }

    public Optional<Logo> logo() {
        Row row = tx.execute(s -> currentRow());
        if (row.logoFile() == null) {
            return Optional.empty();
        }
        Path file = storageRoot.resolve("branding").resolve(row.logoFile()).normalize();
        if (!file.startsWith(storageRoot.normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Logo(Files.readAllBytes(file), row.logoContentType()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Valide le contenu (et non l'en-tête déclaré par le client) : PNG ou SVG sans contenu actif. */
    static String detectType(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidLogoException("Fichier vide");
        }
        if (content.length > MAX_LOGO_BYTES) {
            throw new InvalidLogoException("Logo trop volumineux (512 Ko maximum)");
        }
        if (content.length >= PNG_MAGIC.length
                && Arrays.equals(Arrays.copyOf(content, PNG_MAGIC.length), PNG_MAGIC)) {
            return "image/png";
        }
        String text = new String(content, StandardCharsets.UTF_8);
        String head = text.stripLeading();
        if ((head.startsWith("<?xml") || head.startsWith("<svg")) && text.contains("<svg")) {
            if (SVG_FORBIDDEN.matcher(text).find()) {
                throw new InvalidLogoException("SVG refusé : contenu actif interdit");
            }
            return "image/svg+xml";
        }
        throw new InvalidLogoException("Format non pris en charge (PNG ou SVG uniquement)");
    }

    private Row currentRow() {
        return jdbc.sql("""
                select primary_color, theme, logo_file, logo_content_type
                from tenant_branding where tenant_id = app_current_tenant()
                """).query(Row.class).optional()
                .orElse(new Row("#1F4E79", "system", null, null));
    }
}
