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
`KeycloakLoginIT` démarre un vrai Keycloak (`quay.io/keycloak/keycloak:26.0.7`, autre image via
`-Dessor.keycloak.image=keycloak/keycloak:26.0.7`) avec `deploy/keycloak/realm-essor.json` et joue la connexion
BFF (code + PKCE) jusqu'à `/api/me`, avec le profil `demo`.
Sans Docker, sur un PostgreSQL 16 local (base remise à zéro par les tests) :
```bash
ESSOR_TEST_DB_URL=jdbc:postgresql://localhost:5432/essor_test \
ESSOR_TEST_DB_OWNER=essor_owner ESSOR_TEST_DB_OWNER_PASSWORD=owner mvn -B verify
```
Le rôle propriétaire doit pouvoir créer des rôles (le test crée `essor_app`).

## Configuration

Variables d'environnement : voir `application.yml` et `../deploy/.env.example`. Aucun secret par défaut.
