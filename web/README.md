# ESSOR QMS — front-end web

Angular 18 (standalone, signals, SCSS), sans bibliothèque UI. Direction visuelle : `docs/ux/maquettes-v2.html`.

## Commandes

```bash
npm ci
npm start          # http://localhost:4200 (proxifier /api, /oauth2, /logout vers le back-end)
npm run lint
npm test           # Karma + Chrome headless (CHROME_BIN si besoin)
npm run build      # dist/web/browser
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
  sélecteur de site (sites de `/api/me`), panneau de notifications (vide).
- `features/mes-actions` : accueil, **données d'exemple** (`mes-actions.exemple.ts`), affichées comme telles. Métier en M1.
- Design system : variables CSS dans `src/styles.scss`. Polices Archivo et IBM Plex Mono embarquées via `@fontsource` (aucun CDN).
