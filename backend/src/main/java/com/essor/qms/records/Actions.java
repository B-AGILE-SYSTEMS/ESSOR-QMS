package com.essor.qms.records;

import com.essor.qms.shared.ApiException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API du module {@code records} pour les autres modules : fiches ACTION (M1) rattachées à un objet hors moteur
 * (risque, texte réglementaire, évaluation fournisseur). Pas de fiche parente : le lien est dans {@code data}
 * ({@code origine}, identifiant de l'objet) et dans la table de liaison du module appelant.
 */
@Service
public class Actions {

    /** Résumé d'une action pour les fiches des autres modules. */
    public record ActionRef(UUID id, String reference, String title, String status, UUID ownerId, String ownerName,
                            LocalDate dueDate) {
    }

    private final RecordService records;
    private final JdbcClient jdbc;

    Actions(RecordService records, JdbcClient jdbc) {
        this.records = records;
        this.jdbc = jdbc;
    }

    /** Crée l'action (notifie le responsable) ; {@code ownerId} nul = utilisateur courant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(String title, String description, UUID siteId, UUID ownerId, LocalDate dueDate,
                       String actionType, Map<String, Object> data) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw ApiException.badRequest("Titre de l'action obligatoire (200 caractères maximum)");
        }
        return records.createStandaloneAction(title, description, siteId, ownerId, dueDate,
                actionType == null || actionType.isBlank() ? "corrective" : actionType, data);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ActionRef> refs(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select r.id, r.reference, r.title, r.status, r.owner_id, coalesce(u.display_name, u.username),
                       r.due_date
                from record r left join app_user u on u.id = r.owner_id
                where r.id = any(:ids) and r.type_code = 'ACTION' order by r.created_at, r.reference""")
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, i) -> new ActionRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6),
                        rs.getObject(7, LocalDate.class))).list();
    }
}
