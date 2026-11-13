package com.essor.qms.tenancy;

import com.essor.qms.tenancy.BrandingService.Branding;
import com.essor.qms.tenancy.BrandingService.InvalidLogoException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tenant/branding")
class BrandingController {

    record UpdateBranding(
            @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
            @NotNull @Pattern(regexp = "^(clair|sombre|terrain)$") String theme) {
    }

    private final BrandingService branding;

    BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping
    Branding get() {
        return branding.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    Branding update(@Valid @RequestBody UpdateBranding body) {
        return branding.update(body.primaryColor(), body.theme());
    }

    @PostMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    Branding uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > BrandingService.MAX_LOGO_BYTES) {
            throw new InvalidLogoException("Logo trop volumineux (512 Ko maximum)");
        }
        return branding.storeLogo(file.getBytes());
    }

    @GetMapping("/logo")
    ResponseEntity<byte[]> logo() {
        return branding.logo()
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.contentType()))
                        .cacheControl(CacheControl.noCache())
                        // un SVG ouvert directement ne peut exécuter aucun script
                        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                        .body(l.content()))
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(InvalidLogoException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(InvalidLogoException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    Map<String, String> tooLarge() {
        return Map.of("error", "Logo trop volumineux (512 Ko maximum)");
    }
}
