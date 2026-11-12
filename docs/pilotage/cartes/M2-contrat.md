# Contrat M2 — Mobile terrain (hors ligne puis synchronisé)

Démo : sans réseau, un opérateur signale un presqu'accident avec 2 photos et marque une action faite ; au retour du réseau, tout se synchronise ; le responsable QHSE voit l'incident et les photos sur le web en temps réel.

## Nouveaux types de fiche (moteur M1, aucune logique codée par type)
- `INCIDENT` (préfixe INC) : catégories `accident_travail|presqu_accident|situation_dangereuse|environnement|materiel`, champs `data.lieu`, `data.blesses` (bool), `data.temoins`. Workflow : DECLARE → EN_ANALYSE → ACTIONS → CLOS (+ CLASSE_SANS_SUITE). Les actions M1 s'y rattachent.
- La NC M1 peut aussi être déclarée depuis le mobile.

## Idempotence et synchronisation
- Toute création ou transition envoyée par le mobile porte l'en-tête `Idempotency-Key` (UUID généré sur le téléphone). Le serveur garde la réponse 48 h par (tenant, clé) : un renvoi rend la même réponse sans doublon.
- `POST /api/records` accepte `clientId` (UUID) et `occurredAt` (date terrain, distincte de `createdAt`).
- Conflit : une transition devenue impossible renvoie 409 ; le mobile marque l'opération « à revoir » avec le message, sans bloquer la file.
- `GET /api/sync/changes?since=<horodatage>` → `{records:[RecordSummary], serverTime}` : fiches où l'utilisateur est responsable ou déclarant, modifiées depuis.

## Pièces jointes
- `POST /api/records/{id}/attachments` (multipart `file`, JPEG/PNG/PDF ≤ 10 Mo, en-tête `Idempotency-Key`) → `{id, fileName, contentType, size, createdAt, createdByName}`. Stockage disque local (même répertoire que les logos), contrôle du contenu réel, RLS.
- `GET /api/records/{id}/attachments` ; `GET /api/attachments/{id}` (téléchargement, `Content-Disposition`, `nosniff`).
- Les images sont redimensionnées côté mobile (1600 px max, JPEG 80 %) avant envoi.

## Mobile
- File d'opérations Drift : création, transition, pièce jointe ; envoi dans l'ordre, reprise après échec, état visible (en attente / envoyé / à revoir).
- Écrans : accueil (maquette), « Signaler » en 3 étapes (type, lieu détecté ou choisi, description + photos ; dictée vocale du système), « Mes tâches » (my-tasks en cache, marquer une action faite hors ligne), détail d'une fiche (lecture), scan QR d'une zone ou d'un site (`essor://site/<code>` ou `essor://zone/<code>`) pour préremplir le lieu.
- Temps réel sans cloud : à l'ouverture et toutes les 2 min au premier plan, appel de `sync/changes` ; pas de push Google/Apple.
- Rafraîchissement du profil au retour du réseau ; logo mis en cache.

## Web
- Fiche : onglet « Pièces jointes » (vignettes, aperçu, téléchargement, ajout).
- Menu Terrain → « Incidents & sécurité » : liste et fiche (même composant générique que la NC, libellés du type).
- Badge « déclaré depuis le mobile » et date terrain (`occurredAt`).
