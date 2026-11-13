package com.essor.qms.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = "cle-de-test-0123456789-0123456789-abcdef";

    @Test
    void chiffreEtDechiffreAvecIvAleatoire() {
        SecretCipher c = new SecretCipher(KEY);
        String a = c.encrypt("secret-hmac");
        String b = c.encrypt("secret-hmac");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("secret-hmac");
        assertThat(c.decrypt(a)).isEqualTo("secret-hmac");
        assertThat(c.decrypt("ancien-en-clair")).isEqualTo("ancien-en-clair");
    }

    @Test
    void refuseUneCleAbsenteOuCourteEtUneAutreCle() {
        assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("courte")).isInstanceOf(IllegalStateException.class);
        String enc = new SecretCipher(KEY).encrypt("x");
        assertThatThrownBy(() -> new SecretCipher(KEY + "-autre").decrypt(enc))
                .isInstanceOf(IllegalStateException.class);
    }
}
