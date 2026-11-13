# ESSOR QMS — back-end

Java 21, Spring Boot 3.3, Spring Modulith, PostgreSQL 16 (Flyway, RLS), Keycloak (OIDC).

## Modules (Spring Modulith)

| Module     | Contenu |
|------------|---------|
| `shared`   | contexte d'exécution (utilisateur, tenant), gestionnaire de transactions RLS, journal d'audit, santé |
| `tenancy`  | tenants, sites, habillage (branding, logo sur disque) |
| `identity` | sécurité (BFF + Bearer JWT), rôles Keycloak, provisionnement à la volée, `/api/me`, `/api/users` |
| `records`  | moteur générique de fiches : types, workflow JSON, fiches (NC, actions), historique, « Mes actions », tableau de bord, tâche planifiée des échéances ; publie `RecordEvents` |
| `notifications` | notifications in-app, SSE, e-mail SMTP, règles événement × canal ; écoute `RecordEvents` |

`identity` dépend de `tenancy`, `notifications` de `records` (événements) ; `shared` est partagé. `ModularityTest` vérifie ces frontières.

## API (M0)

| Méthode | Chemin | Accès |
|---|---|---|
| GET | `/api/health` → `{"status":"UP"}` | public |
| GET | `/api/me` → `{username, displayName, tenant:{id,code,name}, sites:[{id,code,name}], roles}` | authentifié |
| GET / POST | `/api/tenants` (`{code,name}`) | ADMIN |
| GET | `/api/tenant/branding` → `{logoUrl, primaryColor, theme}` | authentifié |
| PUT | `/api/tenant/branding` (`{primaryColor:"#RRGGBB", theme:"clair|sombre|terrain"}`) | ADMIN |
| POST | `/api/tenant/branding/logo` (multipart `file`, PNG, SVG ou JPEG ≤ 512 Ko) | ADMIN |
| GET | `/api/tenant/branding/logo` | authentifié |
| GET | `/oauth2/authorization/keycloak` démarre la connexion web ; `POST /logout` | — |

Web : session `SESSION`, CSRF cookie `XSRF-TOKEN` / en-tête `X-XSRF-TOKEN`. `POST /logout` avec
`Accept: application/json` renvoie `{"logoutUrl": ...}` (URL de déconnexion Keycloak à suivre), sinon redirige.
Mobile : `Authorization: Bearer <jwt>` (client Keycloak `essor-mobile`), sans session ni CSRF.

## API (M1) — voir `docs/pilotage/cartes/M1-contrat.md`

| Méthode | Chemin | Accès |
|---|---|---|
| GET | `/api/record-types` ; `/api/record-types/{code}/workflow` | authentifié |
| PUT | `/api/record-types/{code}/workflow` (validé ; refus si un état utilisé disparaît) | ADMIN |
| GET | `/api/records?type&status&severity&siteId&q&page&size` (size ≤ 100) → `{items,total,page,size}` | authentifié |
| POST | `/api/records` `{type,siteId,title,description,severity,data}` → 201 `Record` | sauf READER |
| GET / PATCH | `/api/records/{id}` (PATCH : champs présents remplacés, `data` fusionné ; 409 si état final) | — / sauf READER |
| POST | `/api/records/{id}/transitions/{code}` `{comment}` (403 rôle, 409 état ou manques listés) | rôles de la transition |
| POST | `/api/records/{id}/actions` `{actionType,title,ownerId,dueDate}` → 201 | sauf READER |
| GET | `/api/records/{id}/history` → `[{at,author,action,transition,comment,diff}]` | authentifié |
| GET | `/api/my-tasks`, `/api/users?q=`, `/api/dashboard/summary` | authentifié |
| GET | `/api/notifications?unread=&page=&size=` → `{items,total,unread,page,size}` | destinataire |
| POST | `/api/notifications/{id}/read`, `/api/notifications/read-all` (204) | destinataire |
| GET | `/api/notifications/stream` (SSE : `event: ready`, puis `event: notification`) | authentifié |
| GET / PUT | `/api/notification-rules` `[{event,channel,enabled}]` | ADMIN |

Erreurs : `{"error","message","details"}` (`invalid`, `not_found`, `forbidden`, `transition_impossible`,
`requirements_missing`, `record_locked`, `invalid_workflow`, `state_in_use`).

Workflow NC (modèle `record_type_template`, copié par tenant) : DECLAREE → QUALIFIEE → ANALYSE → ACTIONS →
VERIFICATION → CLOTUREE (+ REJETEE). Transitions `qualifier` (exige gravité et responsable), `rejeter`,
`lancer_analyse`, `valider_analyse` (exige `data.causeRacine`), `renvoyer_analyse`, `lancer_verification`
(exige `childrenIn` : toutes les actions FAITE ou VERIFIEE, au moins une), `cloturer`, `rouvrir_actions`.
Action : A_FAIRE → EN_COURS → FAITE → VERIFIEE. Rôles realm mémorisés dans `app_user.roles` à chaque connexion.
Notifications : assignation, transition (responsable + rôles des transitions suivantes, sauf l'auteur), retard et
échéance J-2 (cron `ESSOR_DEADLINE_CRON`, 6 h par défaut, sans doublon). E-mail si `ESSOR_SMTP_HOST` est défini.

Données de démo : profil `demo` (`classpath:db/demo`) — sites USINE-CASA et ENTREPOT-TNG, 6 NC à tous les statuts
et leurs actions ; comptes préparés `pending:<username>` rattachés au compte Keycloak du même nom.

## API (M2) — voir `docs/pilotage/cartes/M2-contrat.md`

| Méthode | Chemin | Accès |
|---|---|---|
| POST | `/api/records` accepte en plus `clientId` (UUID, unique par tenant : un renvoi rend la fiche existante) et `occurredAt` (ISO-8601, au plus 5 min dans le futur) | sauf READER |
| GET | `/api/sync/changes?since=<ISO-8601>` → `{records:[RecordSummary], serverTime}` (responsable ou déclarant, `updated_at > since`, 500 max) | authentifié |
| POST | `/api/records/{id}/attachments` (multipart `file`, JPEG/PNG/PDF ≤ 10 Mo, type vérifié sur le contenu) → 201 `{id,fileName,contentType,size,createdAt,createdByName}` | sauf READER |
| GET | `/api/records/{id}/attachments` → liste ; `/api/attachments/{id}` (inline, `?download=true` : attachment ; `nosniff`) | authentifié |

`RecordSummary` et `Record` gagnent `clientId`, `occurredAt`, `updatedAt` ; `RecordSummary` (dont `/api/my-tasks`) et les notifications gagnent `recordType` (badge « mobile » : `clientId` non nul).
Idempotence : tout POST `/api/**` portant `Idempotency-Key` (UUID) est mémorisé 48 h par (tenant, clé) dans
`idempotency_key` ; un renvoi rend le même statut et le même corps (en-tête `Idempotent-Replayed: true`).
Clé réutilisée pour une autre URL : 409 `idempotency_key_reused` ; requête jumelle en cours : 409
`idempotency_in_progress` ; 5xx non mémorisées. Fichier trop gros : 413 `too_large`.
Type INCIDENT (préfixe INC, champs `data.categorie|lieu|blesses|temoins`) : DECLARE → EN_ANALYSE (`analyser`,
exige un responsable) → ACTIONS (`planifier_actions`) → CLOS (`clore`, actions FAITE/VERIFIEE) ;
`classer` / `classer_apres_analyse` → CLASSE_SANS_SUITE. Pièces jointes : `<essor.storage.dir>/attachments/<tenant>/<id>`.

## API (M3) — voir `docs/pilotage/cartes/M3-contrat.md`

| Méthode | Chemin | Accès |
|---|---|---|
| POST | `/api/standards/import?dryRun=` multipart `file` (xlsx ou csv `;`, en-tête `code;titre;texte;parent;auditable`, ≤ 5 Mo) + `standardId` ou `standardCode`, `standardVersion`, `standardTitle` → `{standardId, created, updated, errors:[{line,message}], applied, dryRun}` (tout ou rien : une erreur = rien d'écrit) | ADMIN, QUALITY_MANAGER |
| GET | `/api/standards/import/template?format=xlsx|csv` ; `/api/standards` → `[{id,code,version,title,requirementCount}]` | authentifié |
| GET | `/api/standards/{id}/requirements` → arbre `[{id,code,title,text,auditable,position,children}]` | authentifié |
| GET | `/api/requirements/{id}` → `{..., standard, parent, children, documents, records, findings}` | authentifié |
| GET / PUT | `/api/records/{id}/requirements` (`[requirementId]`, remplace) → `[{id,code,title,standardId,standardCode}]` | — / sauf READER |
| GET | `/api/audits?year=&status=PLANIFIE,EN_COURS&auditor=me` (programme ; année de `data.datePrevue`) ; `/api/audits/{id}` → `{..., questions, ncs, summary}` | authentifié |
| GET | `/api/audits/{id}/checklist` → `[{id,requirementId,requirementCode,requirementTitle,position,text,question,result,comment,evidence,closed,answeredAt,answeredByName,ncId,ncReference}]` | authentifié |
| POST | `/api/audits/{id}/checklist/generate` `{standardIds, requirementIds?}` (une question par exigence auditable, sans doublon) ; `/api/audits/{id}/questions` `{text,requirementId?,position?}` ; PATCH / DELETE `/api/audits/{id}/questions/{qid}` | AUDITOR, QUALITY_MANAGER, ADMIN |
| PUT | `/api/audits/{id}/answers/{qid}` `{result,comment,evidence,closed?}` (`Idempotency-Key` accepté) ; `closed:true` sur un écart crée la NC aussitôt | idem |
| GET | `/api/audits/{id}/report.pdf` (OpenPDF : logo PNG/JPEG et couleur du tenant, synthèse, taux par chapitre, constats, NC) | authentifié |
| POST | `/api/documents` `{code,title,docType,processus,siteId?,ownerId?,recipients:{roles,siteIds},verifierRoles,approverRoles,reviewDate,requirementIds}` → 201 `{document, ownerId, ..., versions}` | sauf READER |
| GET | `/api/documents?status=&type=&q=&inForce=` → `[{id,reference,code,title,docType,processus,status,statusLabel,currentVersion:{id,number,fileName}|null,latestVersion,reviewDate,updatedAt}]` ; `/api/documents/{id}` | authentifié |
| POST | `/api/documents/{id}/versions` multipart `file` (PDF/DOCX/XLSX ≤ 20 Mo) + `note` → 201 (409 `version_in_progress` si une version est en cours) | sauf READER |
| POST | `/api/documents/{id}/versions/{n}/transitions/{code}` `{comment}` : `soumettre`, `verifier` / `refuser_verification` (rôles `verifierRoles`), `approuver` / `refuser_approbation` (rôles `approverRoles`), `archiver` | rôles |
| GET | `/api/documents/{id}/versions/{n}/file?download=` (nosniff) | authentifié |
| POST / GET | `/api/documents/{id}/versions/{n}/ack` (destinataire, version en vigueur) ; `.../acks` → `{version, acknowledged:[{userId,name,ackedAt}], pending:[...]}` | destinataire / authentifié |

Type AUDIT (préfixe AUD) : PLANIFIE → EN_COURS (`demarrer`) → RAPPORT (`rediger_rapport`, crée les NC des écarts
restants ; `reprendre` revient en cours) → CLOTURE (`cloturer`). Taux de conformité = (conforme + observation) /
réponses hors « na ». NC d'écart : site de l'audit, gravité majeure/mineure, `data.auditId|questionId|requirementId`,
liée à l'exigence. Type DOCUMENT (préfixe DOC) : le statut de la fiche suit sa dernière version ; transitions
génériques refusées (409). Version approuvée = DIFFUSE (en vigueur, index unique), la précédente devient PERIME.
Notifications `DOCUMENT_DIFFUSED` (destinataires : rôles ou sites) et `REVIEW_DUE` (responsable, `data.reviewDate`
à J-30 au plus, une fois par date, tâche quotidienne M1). Fichiers : `<essor.storage.dir>/documents/<tenant>/<id>`.
Démo (profil `demo`) : ISO 9001:2015 et ISO 45001:2018 (titres, résumés maison), un audit planifié, PR-QHSE-07.
RLS FORCE sur `standard`, `requirement`, `record_requirement`, `audit_question`, `document_version`, `document_ack`.

## Multi-tenant et RLS

- Un utilisateur appartient à un seul tenant (`app_user.tenant_id`), donné par l'attribut Keycloak `tenant`
  (à défaut `essor.tenancy.default-tenant-code`). Provisionné à la première requête, rattaché aux sites du tenant.
- `ExecutionContextFilter` positionne le contexte ; `TenantAwareTransactionManager` exécute en début de chaque
  transaction `set_config('app.tenant_id', ..., true)` (= `SET LOCAL`) et `app.user_id`.
- Politiques RLS (`FORCE`) sur `site`, `app_user`, `user_site`, `tenant_branding`, `audit_log`, `record_type`,
  `record_sequence`, `record`, `notification`, `notification_rule`. Sans contexte :
  aucune ligne. `tenant` (registre) n'est pas tenant-scopé : il sert à résoudre le tenant à la connexion.
- L'application se connecte en `essor_app` (non propriétaire) ; Flyway en propriétaire.
- `audit_log` : ajout seul (droits + déclencheur qui refuse UPDATE/DELETE/TRUNCATE).
- **Toute requête sur une table tenant-scopée doit être dans une transaction** (sinon elle ne voit rien).

## Construire et tester

```bash
mvn -B verify            # tests unitaires + intégration (Testcontainers, Docker requis)
```
`KeycloakLoginIT` démarre un vrai Keycloak (`quay.io/keycloak/keycloak:26.8.2`, autre image via
`-Dessor.keycloak.image=keycloak/keycloak:26.8.2`) avec `deploy/keycloak/realm-essor.json` et joue la connexion
BFF (code + PKCE) jusqu'à `/api/me`, avec le profil `demo`.
Sans Docker, sur un PostgreSQL 16 local (base remise à zéro par les tests) :
```bash
ESSOR_TEST_DB_URL=jdbc:postgresql://localhost:5432/essor_test \
ESSOR_TEST_DB_OWNER=essor_owner ESSOR_TEST_DB_OWNER_PASSWORD=owner mvn -B verify
```
Le rôle propriétaire doit pouvoir créer des rôles (le test crée `essor_app`).

Analyse OWASP des dépendances (non bloquante, base NVD requise) : `mvn org.owasp:dependency-check-maven:check`,
rapport dans `target/dependency-check-report.html` (job CI `dependency-check`).

Fiche (`GET /api/records/{id}`) : `createdBy` (identifiant) et `createdByName` (nom lisible) ; historique : `author` = nom lisible.

## Configuration

Variables d'environnement : voir `application.yml` et `../deploy/.env.example`. Aucun secret par défaut.
