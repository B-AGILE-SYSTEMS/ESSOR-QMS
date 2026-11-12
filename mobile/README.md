# ESSOR QMS — application mobile (Flutter)

Application terrain Android et iOS, hors ligne d'abord.

## Architecture
- **Riverpod** : `lib/core/providers.dart` (dépendances, session `SessionController`).
- **go_router** : `lib/app.dart`. La route suit l'état de session : chargement → serveur → connexion → accueil.
- **Dio** : `lib/core/api/api_client.dart`. Ajoute le Bearer et renouvelle le jeton sur 401.
- **Drift (SQLite)** : `lib/core/sync/sync_database.dart` (file `pending_operations`) et `sync_service.dart` (envoi dans l'ordre, arrêt à la première erreur). En M0, l'envoi reste en attente (`NotYetAvailableUploader`). Il sera branché en M2.

## Connexion
- Au premier lancement, l'utilisateur saisit l'URL du serveur on-premise (ex. `https://qms.usine.local`). Elle est gardée en stockage sécurisé.
- La connexion passe par OIDC avec PKCE (`flutter_appauth`) : issuer `<serveur>/auth/realms/essor`, client `essor-mobile`, redirection `ma.bagile.essor:/oauthredirect`. Ce schéma est déclaré dans `android/app/build.gradle.kts` et `ios/Runner/Info.plist`. Le client Keycloak doit l'autoriser.
- Les jetons sont gardés uniquement dans `flutter_secure_storage`.
- Après la connexion, l'application appelle `GET /api/me` et `GET /api/tenant/branding`, puis applique le logo, la couleur principale et le thème (`clair`, `sombre`, `terrain` = contraste maximal).

## Hors ligne au démarrage
- Après chaque chargement réussi, le profil (`/api/me`) et l'apparence sont mis en cache dans le stockage sécurisé (`lib/core/api/profile_cache.dart`), liés à l'URL du serveur.
- Si le serveur est injoignable au démarrage, l'application ouvre l'accueil avec le dernier profil connu ; la barre d'état affiche « Hors ligne ».
- Seul un 401 reçu du serveur (jeton révoqué, renouvellement refusé) efface jetons et cache et renvoie vers la connexion. La déconnexion et le changement de serveur effacent aussi le cache.
- Sans cache (premier usage hors ligne), l'application renvoie vers la connexion.

## Accueil terrain
Il suit l'écran 1 de la maquette mobile : barre d'état hors ligne / synchro toujours visible, tuiles, gros boutons en bas (84 px et 64 px). Les actions sont simulées : elles sont mises dans la file locale (M2).

## Commandes
```bash
flutter pub get
dart run build_runner build --delete-conflicting-outputs   # après modification des tables Drift
flutter analyze
flutter test
flutter build apk --debug
```
Le code généré (`*.g.dart`) est versionné. La CI vérifie qu'il est à jour (`.github/workflows/mobile.yml`).

## Limites M0
- Le profil en cache n'est pas rafraîchi automatiquement au retour du réseau : il l'est au prochain démarrage.
- Le logo n'est pas mis en cache : hors ligne, les initiales du client s'affichent à sa place si l'image ne charge pas.
- HTTP en clair : Android et iOS le bloquent par défaut. Le serveur doit être en HTTPS (certificat interne installé sur les terminaux).
