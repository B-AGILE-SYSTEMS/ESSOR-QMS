# ESSOR QMS — back-end

Java 21, Spring Boot 3.3, Spring Modulith, PostgreSQL 16 (Flyway, RLS), Keycloak (OIDC).

## Modules (Spring Modulith)

| Module     | Contenu |
|------------|---------|
| `shared`   | contexte d'exécution (utilisateur, tenant), gestionnaire de transactions RLS, journal d'audit, santé |
| `tenancy`  | tenants, sites, habillage (branding, logo sur disque) |
| `identity` | sécurité (BFF + Bearer JWT), rôles Keycloak, provisionnement à la volée, `/api/me` |

`identity` dépend de `tenancy` ; `shared` est partagé. `ModularityTest` vérifie ces frontières.

## API (M0)

| Méthode | Chemin | Accès |
|---|---|---|
| GET | `/api/health` → `{"status":"UP"}` | public |
| GET | `/api/me` → `{username, displayName, tenant:{id,code,name}, sites:[{id,code,name}], roles}` | authentifié |
| GET / POST | `/api/tenants` (`{code,name}`) | ADMIN |
| GET | `/api/tenant/branding` → `{logoUrl, primaryColor, theme}` | authentifié |
| PUT | `/api/tenant/branding` (`{primaryColor:"#RRGGBB", theme:"light|dark|system"}`) | ADMIN |
| POST | `/api/tenant/branding/logo` (multipart `file`, PNG ou SVG ≤ 512 Ko) | ADMIN |
| GET | `/api/tenant/branding/logo` | authentifié |
| GET | `/oauth2/authorization/keycloak` démarre la connexion web ; `POST /logout` | — |

Web : session `SESSION`, CSRF cookie `XSRF-TOKEN` / en-tête `X-XSRF-TOKEN`. `POST /logout` avec
`Accept: application/json` renvoie `{"logoutUrl": ...}` (URL de déconnexion Keycloak à suivre), sinon redirige.
Mobile : `Authorization: Bearer <jwt>` (client Keycloak `essor-mobile`), sans session ni CSRF.

## Multi-tenant et RLS

- Un utilisateur appartient à un seul tenant (`app_user.tenant_id`), donné par l'attribut Keycloak `tenant`
  (à défaut `essor.tenancy.default-tenant-code`). Provisionné à la première requête, rattaché aux sites du tenant.
- `ExecutionContextFilter` positionne le contexte ; `TenantAwareTransactionManager` exécute en début de chaque
  transaction `set_config('app.tenant_id', ..., true)` (= `SET LOCAL`) et `app.user_id`.
- Politiques RLS (`FORCE`) sur `site`, `app_user`, `user_site`, `tenant_branding`, `audit_log`. Sans contexte :
  aucune ligne. `tenant` (registre) n'est pas tenant-scopé : il sert à résoudre le tenant à la connexion.
- L'application se connecte en `essor_app` (non propriétaire) ; Flyway en propriétaire.
- `audit_log` : ajout seul (droits + déclencheur qui refuse UPDATE/DELETE/TRUNCATE).
- **Toute requête sur une table tenant-scopée doit être dans une transaction** (sinon elle ne voit rien).

## Construire et tester

```bash
mvn -B verify            # tests unitaires + intégration (Testcontainers, Docker requis)
```
Sans Docker, sur un PostgreSQL 16 local (base remise à zéro par les tests) :
```bash
ESSOR_TEST_DB_URL=jdbc:postgresql://localhost:5432/essor_test \
ESSOR_TEST_DB_OWNER=essor_owner ESSOR_TEST_DB_OWNER_PASSWORD=owner mvn -B verify
```
Le rôle propriétaire doit pouvoir créer des rôles (le test crée `essor_app`).

## Configuration

Variables d'environnement : voir `application.yml` et `../deploy/.env.example`. Aucun secret par défaut.
