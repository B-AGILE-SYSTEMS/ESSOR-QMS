package com.essor.qms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.essor.qms.tenancy.BrandingService.InvalidLogoException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LogoValidationTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void png_reconnu_par_signature() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
        assertThat(BrandingService.detectType(png)).isEqualTo("image/png");
    }

    @Test
    void svg_simple_accepte() {
        assertThat(BrandingService.detectType(b("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
                .isEqualTo("image/svg+xml");
    }

    @Test
    void svg_actif_refuse() {
        for (String evil : new String[] {
                "<svg><script>x</script></svg>",
                "<svg onload=\"x()\"/>",
                "<svg><a href=\"javascript:x()\"/></svg>",
                "<svg><foreignObject/></svg>",
                "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg/>"}) {
            assertThatThrownBy(() -> BrandingService.detectType(b(evil))).isInstanceOf(InvalidLogoException.class);
        }
    }

    @Test
    void format_inconnu_vide_ou_trop_gros_refuse() {
        assertThatThrownBy(() -> BrandingService.detectType(b("GIF89a"))).isInstanceOf(InvalidLogoException.class);
        assertThatThrownBy(() -> BrandingService.detectType(new byte[0])).isInstanceOf(InvalidLogoException.class);
        assertThatThrownBy(() -> BrandingService.detectType(new byte[512 * 1024 + 1]))
                .isInstanceOf(InvalidLogoException.class);
    }
}
