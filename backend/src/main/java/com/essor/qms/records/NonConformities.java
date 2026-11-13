package com.essor.qms.records;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API du module {@code records} pour les autres modules : création d'une NC (type {@code NC}) par le moteur
 * générique, dans la transaction de l'appelant (ex. décision de contrôle d'un lot, module {@code traceability}).
 */
@Service
public class NonConformities {

    private final RecordService records;

    NonConformities(RecordService records) {
        this.records = records;
    }

    /** Rend l'id de la NC créée ; {@code data} est stocké tel quel dans la fiche (liens lot, fournisseur...). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(String title, String description, String severity, UUID siteId, Map<String, Object> data,
                       Map<String, Object> auditDetails) {
        return records.createInternal("NC", title, description, severity, siteId, null, data, auditDetails);
    }
}
