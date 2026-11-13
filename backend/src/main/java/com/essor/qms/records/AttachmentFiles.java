package com.essor.qms.records;

/**
 * API du module {@code records} : mêmes règles de fichier que les pièces jointes M2 (JPEG, PNG ou PDF de 10 Mo au
 * plus, type vérifié sur le contenu réel, nom nettoyé), réutilisées par les photos de contrôle.
 */
public final class AttachmentFiles {

    public static final long MAX_BYTES = AttachmentService.MAX_BYTES;

    private AttachmentFiles() {
    }

    /** Type réel d'après le contenu ; lève une erreur 400/413 sinon. */
    public static String detectType(byte[] content, String declaredType) {
        return AttachmentService.detectType(content, declaredType);
    }

    public static String safeName(String original, String type) {
        return AttachmentService.safeName(original, type);
    }

    public static RuntimeException tooLarge() {
        return AttachmentService.tooLarge();
    }
}
