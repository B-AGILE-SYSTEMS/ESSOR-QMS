# ESSOR QMS — front-end web

Angular 22.2 (22.2.4, versions épinglées : dernier correctif 22.x publié depuis au moins 14 jours ; CLI 22.2.5, builder esbuild `@angular/build` 22.2.5 — Webpack et `@angular-devkit/build-angular` retirés —, TypeScript 6.0 ; standalone, signals, OnPush, SCSS), sans bibliothèque UI. Direction visuelle : `docs/ux/maquettes-v2.html`.

Audit : `npm audit --omit=dev --audit-level=high` à 0 ; le job CI « audit » est bloquant. Montée de version : passer par chaque majeure avec `ng update` et choisir un correctif publié depuis au moins 14 jours.

## Commandes

```bash
npm ci
npm start          # http://localhost:4200 (proxifier /api, /oauth2, /logout vers le back-end)
npm run lint
npm test           # Karma + Chrome headless (CHROME_BIN si besoin) ; couverture lcov + texte dans coverage/, seuil 60 % lignes
npm audit --omit=dev --audit-level=high   # job CI « audit »
npm run build      # dist/web/browser
npm run e2e        # Playwright contre l'API simulée e2e/mock-api.ts (CHROME_BIN ou `npx playwright install chromium`)
docker build -t essor-qms/web:local .   # Nginx statique, port 80
```

En déploiement : `docker compose --profile web up -d` depuis `deploy/` ; le Nginx frontal sert `/` depuis ce conteneur.

## Architecture

- `core/auth.service.ts` : session BFF. Démarrage par `GET /api/me` ; 401 → `/oauth2/authorization/keycloak`.
  Déconnexion : `POST /logout` avec `Accept: application/json`, puis redirection vers `logoutUrl`.
  CSRF : cookie `XSRF-TOKEN` → en-tête `X-XSRF-TOKEN` (HttpClient). Le navigateur ne détient aucun jeton.
- `core/unauthorized.interceptor.ts` : toute réponse 401 renvoie vers la connexion.
- `core/role.guard.ts` : `canMatch: [roleGuard('ADMIN')]` (Paramétrage → Apparence).
- `core/branding.service.ts` : `GET/PUT /api/tenant/branding`, `POST /api/tenant/branding/logo` (champ `file`,
  PNG/SVG/JPEG ≤ 512 Ko). Thème (`data-skin` = `clair|sombre|terrain`) et `--acc` appliqués en direct sur `<html>`.
- `shell/` : navigation en 5 familles, fil d'Ariane (`data.crumbs` des routes), palette Ctrl K, « + Déclarer une NC »,
  sélecteur de site (sites de `/api/me`), panneau de notifications (`shell/notifications/`).
- `core/records.service.ts` : moteur de fiches (contrat M1 `docs/pilotage/cartes/M1-contrat.md`) ; `core/api.models.ts` : types du contrat ;
  `core/api-error.ts` : message français des erreurs `{error, message, details}` (409 de transition affiché tel quel).
- `core/notifications.service.ts` : liste, compteur non lues, marquer lu / tout lu, temps réel par SSE
  (`/api/notifications/stream`, messages anonymes ou `event: notification`, une notification JSON par message).
- `features/mes-actions` : accueil branché sur `/api/my-tasks` et `/api/dashboard/summary`.
- `features/nc` : liste filtrable/paginée, déclaration, fiche (barre d'étapes issue du workflow, onglets Description,
  Analyse — 5 pourquoi, cause racine et 6M dans `data.analyse5p|causeRacine|ishikawa` —, Actions, Historique).
  Les boutons de transition viennent de `availableTransitions` ; désactivés, ils listent `missing`.
- `features/fiches` (M2, contrat `docs/pilotage/cartes/M2-contrat.md`) : `record-type-view.ts` donne les seuls libellés par type
  (`data.view` des routes) ; la liste et la fiche de `features/nc` sont génériques et servent aussi « Incidents & sécurité »
  (`/incidents`, type `INCIDENT`, champs `data.categorie|lieu|blesses|temoins`). Badge « déclaré depuis le mobile » quand la fiche
  porte `clientId` ; date terrain `occurredAt` distincte de `createdAt`. Onglet « Pièces jointes » (`attachments.component.ts`) :
  `GET/POST /api/records/{id}/attachments` (multipart `file`, en-tête `Idempotency-Key` UUID, JPEG/PNG/PDF ≤ 10 Mo contrôlés
  avant envoi), vignettes et téléchargement par `GET /api/attachments/{id}`.
- `features/parametrage` (ADMIN) : Workflows (diagramme états/transitions, édition JSON validée par le serveur via PUT),
  Règles de notification (matrice événement × canal).
- M3 (contrat `docs/pilotage/cartes/M3-contrat.md`, `core/m3.models.ts`, `core/m3.service.ts`) — la fiche générique `features/nc/nc-fiche`
  sert aussi les types `AUDIT` et `DOCUMENT` ; `RecordTypeView.tabs|startTab|severity` choisit les onglets, sans code par type :
  - `features/referentiels` (ADMIN, Paramétrage → Référentiels) : import xlsx/csv (`file` + `standardCode|standardTitle|standardVersion`),
    simulation `?dryRun=true` obligatoire avant l'import réel (tout ou rien), rapport `{created, updated, errors[line, message]}`,
    modèle téléchargeable, arbre des exigences repliable, vue d'une exigence avec `{documents, records, findings}`.
  - `features/fiches/record-requirements` : onglet « Exigences » (NC, incidents, documents) → `PUT /api/records/{id}/requirements`.
  - `features/audits` : programme annuel en calendrier (`GET /api/audits?year=&auditor=me`), planification (fiche AUDIT, `data.plannedDate|perimetre|auditeurs|audites|standardIds`),
    onglet « Grille d'audit » : génération, édition et suppression de questions (`PATCH|DELETE /api/audits/{id}/questions/{qid}`), exécution
    (`PUT …/answers/{qid}` avec `Idempotency-Key` et `closed`), taux de conformité par chapitre, constats, NC créées, rapport `report.pdf`.
  - `features/documents` : liste (`currentVersion.number`), création (`POST /api/documents`, `data.verifierRoles|approverRoles|destinataires`),
    onglet « Versions » (téléversement PDF/DOCX/XLSX ≤ 20 Mo + note, transitions par NUMÉRO de version
    `POST …/versions/{n}/transitions/{code}`, visionneuse PDF intégrée `…/versions/{n}/file`), onglet « Diffusion & lecture » (accusés).
- Hypothèses de format M3 (non fixées par le contrat, lues de façon tolérante) : listes en tableau ou `{items}` ; arbre des exigences
  imbriqué (`children`) ou plat (`parentId`) ; `GET /api/audits/{id}` porte la grille dans `questions` ; une réponse renvoie la question
  avec `ncId|ncReference` ; `GET …/versions` → `[{number, status, fileName, contentType, current, availableTransitions?}]` (à défaut,
  transitions déduites du statut) ; `GET …/acks` → `{read:[{userId, displayName, at}], pending:[…]}` ; liens d'une fiche dans `requirements`.
- `e2e/` : parcours NC complet, paramétrage, incidents et pièces jointes (contrôle de signature, Idempotency-Key, `sync/changes`), M3
  (import ISO 45001 avec simulation et erreur, audit jusqu'au rapport PDF, cycle d'un document jusqu'à l'accusé de lecture), API simulée
  en mémoire (`page.route`, `mock-api.ts` + `mock-api-m3.ts`), écran 1440 px.
- Design system : variables CSS dans `src/styles.scss`. Polices Archivo et IBM Plex Mono embarquées via `@fontsource` (aucun CDN).
