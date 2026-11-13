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
  - `features/audits` : programme annuel en calendrier (`GET /api/audits?year=&auditor=me`), planification (fiche AUDIT, auditeurs choisis parmi les utilisateurs),
    onglet « Grille d'audit » : génération, édition et suppression de questions (`PATCH|DELETE /api/audits/{id}/questions/{qid}`), exécution
    (`PUT …/answers/{qid}` avec `Idempotency-Key` et `closed`), taux de conformité par chapitre, constats, NC créées, rapport `report.pdf`.
  - `features/documents` : liste (`currentVersion.number`), création (`POST /api/documents`, rôles vérificateurs/approbateurs, destinataires),
    onglet « Versions » (téléversement PDF/DOCX/XLSX ≤ 20 Mo + note, transitions par NUMÉRO de version
    `POST …/versions/{n}/transitions/{code}`, visionneuse PDF intégrée `…/versions/{n}/file`), onglet « Diffusion & lecture » (accusés).
- Formats M3 suivis : ceux du back-end livré (`backend/…/records/StandardService|AuditService|DocumentService`), normalisés dans
  `core/m3.service.ts` : arbre `RequirementNode{position, children}`, détail `{standard, documents, records, findings}`, liens d'une fiche
  `GET /api/records/{id}/requirements` ; programme `ProgramItem{datePrevue, auditeurs:[userId]}` ; grille dans `GET /api/audits/{id}`
  (`questions[{text|question, closed, ncId, ncReference}]`) ; documents `DocumentSummary{code, docType, currentVersion{id, number, fileName}}`,
  versions dans `GET /api/documents/{id}` (`VersionView{number, inForce, availableTransitions}`), accusés `{acknowledged, pending}` de
  `{userId, name, ackedAt}`. Données de fiche : AUDIT `data.datePrevue|perimetre|auditeurs|audites|standardIds`, DOCUMENT
  `data.code|docType|processus|recipients|verifierRoles|approverRoles|reviewDate`.
- `e2e/` : parcours NC complet, paramétrage, incidents et pièces jointes (contrôle de signature, Idempotency-Key, `sync/changes`), M3
  (import ISO 45001 avec simulation et erreur, audit jusqu'au rapport PDF, cycle d'un document jusqu'à l'accusé de lecture), API simulée
  en mémoire (`page.route`, `mock-api.ts` + `mock-api-m3.ts`), écran 1440 px.
- M3b (contrat `docs/pilotage/cartes/M3B-contrat.md`, `core/m3b.models.ts`, `core/m3b.service.ts`) — contrôles des flux et traçabilité :
  - `features/lots` (Ressources → Lots & traçabilité) : liste (`GET /api/lots?q=&status=`), réception (`POST /api/lots`), fiche lot (statut et historique,
    `POST …/status {status, reason}` — DEROGATION réservée à QUALITY_MANAGER, motif exigé —, contrôles du lot, généalogie descendante/ascendante
    `GET …/trace?direction=&depth=`). `rappel.component` : `GET …/recall`, export `…/recall.pdf`, blocage du stock restant après confirmation dans la page
    = `POST …/status QUARANTAINE` pour le lot source et chaque lot impacté non encore bloqué (le serveur émet le webhook).
  - `features/controles` (Terrain → Contrôles) : à faire / en cours / historique, saisie guidée par le plan (verdict selon tolérances, `PUT …/results`
    avec `Idempotency-Key`, décision proposée selon Ac/Re, `POST …/decision`), carte de contrôle SVG X̄/R (`GET /api/inspections/spc`).
  - `features/parametrage` : Plans de contrôle (éditeur de caractéristiques), Données de base & connecteurs (import `?dryRun=true` puis réel, webhook).
  - Formats du back-end livré : blocage `POST /api/lots/{id}/recall/block {reason}` → `{blocked}` ; rappel `{source, lotsImpactes[remaining], …}` ;
    `GET|PUT /api/connectors/webhook {url, secret, enabled}` (secret jamais renvoyé, `secretSet`) et `…/webhook/deliveries` ; inspections
    `?status=&lotId=&point=`, `sampling{lotSize, nqa, codeLetter, planLetter, sampleSize, ac, re}`, statuts A_FAIRE|EN_COURS|TERMINEE, `proposedDecision` ;
    motif obligatoire pour QUARANTAINE|DEROGATION|REFUSE|DETRUIT, commentaire obligatoire pour toute décision ≠ LIBERE ; plan `target{type, id, famille}`,
    PUT = nouvelle version, 409 `plan_outdated` ; SPC `{subgroupSize, points, limits{xbar, range}, sigma, cp, cpk, alerts}`.
  - Hypothèses restantes : `GET /api/inspections/{id}`, `GET /api/master-data/{articles|partners}`, champs des points SPC `{index, mean, range}`.
  - E2E : `e2e/m3b-lots-controles.spec.ts` sur `e2e/mock-api-m3b.ts`.
- Design system : variables CSS dans `src/styles.scss`. Polices Archivo et IBM Plex Mono embarquées via `@fontsource` (aucun CDN).
- M4 (contrat `docs/pilotage/cartes/M4-contrat.md`, `core/m4.models.ts`, `core/m4.service.ts`) — risques, veille, métrologie, partenaires :
  - `features/risques` (Pilotage → Risques) : matrice interactive `GET /api/risks/matrix?siteId=&type=` (grille complétée et colorée par l'échelle,
    clic sur une case → liste), registre, création `POST /api/risks {gross:{values}}`, fiche (cotations, actions M1 liées, historique), DUER PDF.
  - `features/veille` : registre filtrable, taux par site (`GET /api/regulations/compliance`, chemin supposé), fiche texte, évaluation
    `POST …/evaluations {siteId, result, evidence, comment}` — non conforme → action M1 affichée.
  - `features/equipements` : parc, fiche + historique, saisie d'étalonnage (verdict prévisionnel |mesure − nominal| ≤ EMT ; verdict officiel serveur),
    « effet instrument » (`GET …/impact`) et quarantaine `POST /api/instruments/{id}/impact/quarantine {reason, since}` → `{quarantined, skipped, ncId}`. Saisie de contrôle M3b : instruments
    en service non échus proposés, `instrumentId` ajouté au résultat, bloqué/échu refusé avant envoi.
  - `features/partenaires` : liste, campagne `POST /api/evaluation-campaigns`, scorecard (`GET …/scorecard`), évaluation `POST …/evaluations`.
  - Paramétrage : Échelles de risque (`/api/risk-scales`), Grilles d'évaluation (`/api/evaluation-grids`, chemin supposé). Tableau de bord :
    KPI conformité réglementaire et étalonnages sous 15 jours lus dans `/api/dashboard/summary` quand présents.
  - Formats : métrologie prise des exemples réels du back (`core/exemples-m4/`, copie de `origin/m4/back:docs/api-exemples` @a792be7, lus par
    `api-exemples-m4.spec.ts` ; à repointer sur docs/api-exemples à l'intégration). Risques, veille, évaluations : contrat seul, lecteurs `to*`
    tolérants (FR/EN) testés dans `m4.models.spec.ts`. e2e `m4-risques-metrologie.spec.ts` + `mock-api-m4.ts`.
