# Contrat d'interface M0 (partagé par toutes les cartes M0)

- API back-end : port 8080, préfixe `/api`. Santé : `GET /api/health` → `{"status":"UP"}` (public).
- `GET /api/me` (authentifié) → `{"username","displayName","tenant":{"id","code","name"},"sites":[{"id","code","name"}],"roles":[...]}`.
- `GET/PUT /api/tenant/branding` → `{"logoUrl","primaryColor","theme":"clair|sombre|ardoise|terrain"}` (PUT : ADMIN). `POST /api/tenant/branding/logo` (PNG/SVG/JPEG ≤ 512 Ko, disque local), `GET /api/tenant/branding/logo`.
- `GET /api/tenants` (rôle ADMIN) liste ; `POST /api/tenants` crée `{code,name}`.
- Auth web : BFF. Le navigateur n'a jamais de jeton. `GET /oauth2/authorization/keycloak` démarre la connexion, `POST /logout` déconnecte. Cookie de session `SESSION`, CSRF par cookie `XSRF-TOKEN` / en-tête `X-XSRF-TOKEN`.
- Auth mobile : jeton Bearer OIDC (PKCE) émis par Keycloak, client `essor-mobile`. Le back-end accepte aussi les Bearer JWT sur `/api/**`.
- Keycloak : realm `essor`, clients `essor-bff` (confidentiel) et `essor-mobile` (public, PKCE). Rôles realm : `ADMIN`, `QUALITY_MANAGER`, `AUDITOR`, `FIELD_USER`, `READER`. Utilisateurs de démo : `admin/admin`, `qualite/qualite`, `terrain/terrain`.
- Nginx (port 80) : `/` → web, `/api`, `/oauth2`, `/login`, `/logout` → back-end, `/auth` → Keycloak.
- Base : PostgreSQL 16, base `essor`, utilisateur applicatif `essor_app` (non propriétaire, soumis à la RLS), migrations Flyway.
