package com.essor.qms.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets stockés en base (ex. secret HMAC du webhook) : AES-256-GCM, IV aléatoire de 12 octets,
 * format {@code v1:<base64(iv || chiffré)>}. Clé dérivée (SHA-256) de {@code ESSOR_SECRET_KEY}, contrôlée au
 * démarrage (32 caractères au moins).
 */
@Component
public class SecretCipher {

    static final String PREFIX = "v1:";
    static final int MIN_KEY_LENGTH = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${essor.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.strip().length() < MIN_KEY_LENGTH || secretKey.contains("a-changer")) {
            throw new IllegalStateException("ESSOR_SECRET_KEY manquante ou trop courte (" + MIN_KEY_LENGTH
                    + " caractères au moins, ex. openssl rand -base64 32)");
        }
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(secretKey.strip().getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length)
                    .put(iv).put(enc).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Déchiffre une valeur {@code v1:} ; une valeur antérieure non chiffrée est rendue telle quelle. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret chiffré illisible (clé ESSOR_SECRET_KEY changée ?)", e);
        }
    }
}
