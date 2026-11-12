# Carte M0-WEB — Front-end Angular 18
Branche : `m0/web` (à partir de `integration/m0`). Périmètre : `web/`, `.github/workflows/web.yml`, et le service `web` de `deploy/docker-compose.yml` si besoin.
Référence UX obligatoire : `docs/ux/maquettes-v2.html` (direction « salle de contrôle », validée). Contrat : `docs/pilotage/cartes/M0-contrat.md`. Code back-end disponible dans `backend/` (lecture seule).
À livrer :
- Angular 18 standalone, signals, SCSS, sans bibliothèque UI lourde. Design system en variables CSS reprenant la maquette (polices embarquées localement, pas de CDN : on-premise).
- Shell : navigation en 5 familles, fil d'Ariane, palette Ctrl K, bouton « + Déclarer une NC », sélecteur de site, notifications (panneau vide prêt).
- Connexion via le BFF (redirection `/oauth2/authorization/keycloak`, `GET /api/me`, déconnexion `POST /logout` avec `Accept: application/json` puis redirection vers `logoutUrl`), CSRF `XSRF-TOKEN`/`X-XSRF-TOKEN`, 401 → connexion.
- Apparence branchée sur l'API : `GET/PUT /api/tenant/branding`, upload/affichage du logo, thèmes `clair|sombre|terrain`, couleur principale appliquée en direct. Écran Paramétrage → Apparence (ADMIN).
- Écran « Mes actions » (accueil) avec données simulées clairement marquées « exemple » (le métier arrive en M1).
- Tests unitaires (Jest ou Karma headless) sur auth, branding, garde de rôle. `npm run build` et tests verts. Dockerfile web (nginx statique).
- CI `.github/workflows/web.yml` : install, lint, test, build.
