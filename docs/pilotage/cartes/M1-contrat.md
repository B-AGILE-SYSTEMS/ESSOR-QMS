# Contrat d'interface M1 — Moteur de fiches + NC/CAPA + notifications

Livrable de démo : une NC créée → qualifiée → analysée → actions → clôturée, avec notifications.

## Moteur de fiches (générique)
- Un **type de fiche** (`record_type`) est défini par : `code` (ex. `NC`, `ACTION`), libellé, préfixe de référence (`NC-2026-0001`, numérotation par tenant et par année), schéma de champs JSON (champs personnalisés stockés en JSONB `data`), et **workflow** JSON.
- Workflow JSON : `{"initial":"DECLAREE","states":[{"code","label","final":bool}],"transitions":[{"code","label","from","to","roles":[...],"requires":["champ",...]}]}`. Une transition n'est possible que si l'utilisateur a un des rôles, et que les champs requis sont renseignés. Validation du JSON à l'enregistrement.
- Workflow NC livré par défaut (migration de données, modifiable) : DECLAREE → QUALIFIEE → ANALYSE → ACTIONS → VERIFICATION → CLOTUREE (+ REJETEE depuis DECLAREE). Transitions : qualifier, rejeter, valider_analyse, renvoyer_analyse, lancer_verification, cloturer, rouvrir_actions.
- Une **action** (type `ACTION`) est rattachée à une fiche parente : type curative|corrective|preventive, responsable, échéance, statut A_FAIRE → EN_COURS → FAITE (→ VERIFIEE). La transition NC `lancer_verification` exige toutes les actions FAITE.
- Historique immuable de chaque fiche (transition, auteur, date, commentaire, diff de champs) dans `audit_log`.

## API
- `GET /api/record-types` ; `GET/PUT /api/record-types/{code}/workflow` (PUT : ADMIN).
- `GET /api/records?type=NC&status=&severity=&siteId=&q=&page=&size=` → `{items:[RecordSummary], total, page, size}`.
- `POST /api/records` `{type, siteId, title, description, severity:"mineure|majeure|critique", data:{}}` → `Record`.
- `GET /api/records/{id}` → `Record` = `{id, reference, type, status, statusLabel, title, description, severity, siteId, siteName, ownerId, ownerName, dueDate, data, createdAt, createdBy, availableTransitions:[{code,label,missing:[champ]}], children:[RecordSummary]}`.
- `PATCH /api/records/{id}` (champs modifiables selon statut).
- `POST /api/records/{id}/transitions/{code}` `{comment}` → `Record` ; 409 si impossible (message en français listant ce qui manque).
- `POST /api/records/{id}/actions` `{actionType, title, ownerId, dueDate}` → action (fiche enfant).
- `GET /api/records/{id}/history`.
- `GET /api/my-tasks` → fiches où l'utilisateur est responsable ou peut agir, triées par échéance, avec `overdue:bool`.
- `GET /api/users?q=` (sélection d'un responsable dans le tenant).
- `GET /api/dashboard/summary` → `{ncOpen, ncToQualify, actionsOverdue, ncCostMonth}`.
- Erreurs : `{"error":"code","message":"texte français","details":{}}`.

## Notifications
- Table `notification` (tenant, destinataire, type, titre, lien fiche, lue).
- Événements : fiche assignée, transition effectuée (au responsable et aux rôles de l'étape suivante), action en retard (tâche planifiée quotidienne), échéance J-2.
- `GET /api/notifications?unread=` ; `POST /api/notifications/{id}/read` ; `POST /api/notifications/read-all`.
- Temps réel : `GET /api/notifications/stream` (Server-Sent Events, session web).
- E-mail via SMTP configurable (`ESSOR_SMTP_*`), désactivé si non configuré ; règles par événement × canal (in-app, e-mail) dans `notification_rule`, `GET/PUT /api/notification-rules` (ADMIN).
