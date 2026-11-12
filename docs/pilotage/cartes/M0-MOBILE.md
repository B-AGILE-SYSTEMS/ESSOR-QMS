# Carte M0-MOBILE — Socle Flutter
Branche : `m0/mobile` (à partir de `integration/m0`). Périmètre : `mobile/`, `.github/workflows/mobile.yml`.
Référence UX : onglet mobile de `docs/ux/maquettes-v2.html`. Contrat : `docs/pilotage/cartes/M0-contrat.md`.
À livrer :
- Flutter stable, Android + iOS. Architecture : Riverpod, go_router, Dio, Drift (SQLite) pour le hors ligne.
- Connexion OIDC PKCE sur Keycloak (client `essor-mobile`, `flutter_appauth`), URL du serveur saisie au premier lancement (on-premise). Jeton en stockage sécurisé.
- Appel `GET /api/me` et `GET /api/tenant/branding` : logo, couleur et thème appliqués.
- Écran d'accueil terrain conforme à la maquette (gros boutons ≥ 64px en bas, indicateur hors ligne/synchro toujours visible). Actions métiers simulées (M2).
- Socle de synchro : file d'attente locale Drift + service de synchro (squelette testé).
- Tests unitaires et widget. `flutter analyze` et `flutter test` verts. CI `.github/workflows/mobile.yml` (analyze, test, build apk debug).
- Si le SDK Flutter ne peut pas être installé dans l'environnement, écris le code, fais tourner ce qui est possible et dis clairement ce qui n'a été vérifié que par la CI.
