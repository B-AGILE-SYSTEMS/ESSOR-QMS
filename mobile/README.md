# ESSOR QMS — application mobile (Flutter)

Application terrain Android et iOS, hors ligne d'abord.

## Architecture
- **Riverpod** : `lib/core/providers.dart` (dépendances, session `SessionController`).
- **go_router** : `lib/app.dart`. La route suit l'état de session : chargement → serveur → connexion → accueil.
- **Dio** : `lib/core/api/api_client.dart`. Ajoute le Bearer et renouvelle le jeton sur 401.
- **Drift (SQLite)** : `lib/core/sync/sync_database.dart` (file `pending_operations`, correspondance `clientId` → id serveur, cache JSON). Schéma v2, migration depuis v1.

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
Il suit l'écran 1 de la maquette mobile : barre d'état hors ligne / synchro toujours visible, tuiles (« mes tâches » en cache), gros boutons en bas (84 px et 64 px ; toute cible ≥ 56 px).

## M2 — terrain hors ligne puis synchronisé
- **File d'opérations** (`lib/core/sync/`) : `record.create`, `record.transition`, `attachment.upload`. Chaque opération porte sa clé `Idempotency-Key` (UUID), réutilisée à chaque renvoi. Envoi dans l'ordre (`SyncService`) : une erreur réseau ou 5xx arrête l'envoi et on reprend plus tard ; un 4xx (dont 409, 413) marque l'opération « à revoir » avec le message du serveur, sans bloquer la suite ; `idempotency_in_progress` est retenté. États visibles : en attente / envoyé / à revoir (écran « État des envois »).
- **Création** : `POST /api/records` avec `clientId` et `occurredAt` (jamais postérieure à l'heure du téléphone). Les photos d'une fiche créée hors ligne sont envoyées sur l'id serveur obtenu.
- **Signaler** (`lib/features/report/`) en 3 étapes : type (NC ou INCIDENT + `data.categorie`), lieu (QR `essor://site/<code>` ou `essor://zone/<code>`, ou choix du site), description (dictée par le micro du clavier système), photos et gravité.
- **Photos** : réduites à 1600 px, JPEG 80 % (`lib/core/media/image_compress.dart`), rangées dans le dossier de l'application jusqu'à l'envoi.
- **Mes tâches** : `GET /api/my-tasks` en cache ; « Marquer faite » met en file `demarrer` (si À faire) puis `terminer`.
- **Fiche** : lecture `GET /api/records/{id}`, sinon le résumé en cache.
- **Temps réel sans cloud** : à l'ouverture, toutes les 2 min au premier plan et au retour du réseau, envoi de la file puis `GET /api/sync/changes?since=` (pages de 500, rappel avec `since=serverTime`, dédoublonnage par `id`) et rafraîchissement de « mes tâches ». Au retour du réseau, le profil est rechargé. Le logo est mis en cache local.
- **Permissions** : caméra (photos, QR).

## M3 — audits et documents hors ligne (`lib/core/m3/`, `lib/features/audits/`, `lib/features/documents/`)
- **Mes audits** : `GET /api/audits?year=` (audits PLANIFIE ou EN_COURS) et la grille de chacun, mises en cache à chaque cycle de synchro.
- **Exécution** question par question (maquette v2, écran 3) : Conforme / Écart mineur / Écart majeur / Observation / N.A., commentaire, photos. Tout est enregistré sur le téléphone puis envoyé par la file M2 : `audit.answer` → `PUT /api/audits/{id}/answers/{questionId}` (`Idempotency-Key`), photos → `POST /api/records/{auditId}/attachments`. La NC liée est créée par le serveur.
- **Documents en vigueur** : `GET /api/documents?status=DIFFUSE` ; chaque version est téléchargée une fois dans le dossier de l'application (lecture hors ligne avec le lecteur du téléphone, `open_filex`) ; une nouvelle version remplace la copie et l'accusé est à refaire.
- **Accusé de lecture** : `document.ack` → `POST /api/documents/{id}/versions/{v}/ack`, différé tant que le réseau manque ; l'état « Lu » est immédiat.
- **Hypothèses hors contrat** (à confirmer côté back) : lecture de la grille `GET /api/audits/{id}/checklist` ; fichier d'une version `GET /api/documents/{id}/versions/{v}/file` ; version en vigueur exposée dans `currentVersion {id, number, fileName}` de la liste.

## M3b — contrôle de lot hors ligne (`lib/core/m3b/`, `lib/features/controls/`)
- **Scan** (bouton « Contrôler un lot ») : GS1-128 (`lib/core/m3b/gs1.dart` : FNC1 = GS ou notation `(AI)`, préfixe `]C1` ; AI 00 SSCC avec clé de contrôle, 01 GTIN, 10 lot, 17 DLC avec JJ=00 = fin de mois) ou QR `essor://lot/<id>` ; saisie manuelle possible. Le lot est cherché dans le cache (numéro ESSOR ou n° lot fournisseur), sinon `GET /api/lots?q=` / `GET /api/lots/{id}` si le réseau est là.
- **Cache** à chaque cycle : `GET /api/control-plans` et `GET /api/lots?status=EN_ATTENTE`. Plans proposés : ceux dont la cible (`target {type, id, famille}`) correspond à l'article, la famille ou le fournisseur du lot (tous, à défaut).
- **Contrôle guidé** : une caractéristique par écran ; mesure → autant de valeurs que `sampling.sampleSize` (ou `taille` en mode fixe, 1 sinon, ajout possible), verdict automatique selon tolérances (bornes incluses) ; attribut / document → conforme ou non ; instrument obligatoire si `instrumentRequis`.
- **Décision proposée** : critique non conforme → REFUSE, autre écart → QUARANTAINE, sinon LIBERE. DEROGATION n'est offerte qu'au rôle QUALITY_MANAGER. Motif obligatoire pour toute décision autre que LIBERE.
- **File M2** : `inspection.create` → `POST /api/inspections` (id serveur mémorisé), `inspection.results` → `PUT /api/inspections/{id}/results`, `inspection.photo`, `inspection.decision` → `POST /api/inspections/{id}/decision`, chacune avec sa `Idempotency-Key`. Le statut du lot est mis à jour localement tout de suite.
- **Hypothèses hors contrat** : `tolMin`/`tolMax` sont des limites absolues (lues comme écarts au nominal si `tolMax` < nominal) ; photos envoyées à `POST /api/inspections/{id}/attachments` (non décrit par le contrat : un refus passe « à revoir » sans bloquer la file).
- **Limites** : la liste serveur `GET /api/inspections` (contrôles A_FAIRE) et `proposedDecision` ne sont pas encore lues ; la taille ISO 2859 n'est connue hors ligne que si le plan la porte.

## Captures
`SCREENSHOT_DIR=<dossier> FLUTTER_ROOT=<sdk> flutter test test/widget/m3_flow_test.dart` écrit `mes-audits.png`, `audit-question-hors-ligne.png` et `documents-hors-ligne.png`.

`SCREENSHOT_DIR=<dossier> FLUTTER_ROOT=<sdk> flutter test test/widget/m2_flow_test.dart` écrit `accueil.png`, `signalement-etape3-hors-ligne.png` et `mes-taches.png`.

`test/widget/m3b_flow_test.dart` écrit `controle-mesure-hors-ligne.png` et `controle-decision-hors-ligne.png`.

Les tests de bout en bout attendent un état (file vide, texte affiché) et non une durée (`settleUntil`, `settleSynced`, `tapPhoto` dans `test/widget/m2_flow_test.dart`).

## Commandes
```bash
flutter pub get
dart run build_runner build --delete-conflicting-outputs   # après modification des tables Drift
flutter analyze
flutter test
flutter build apk --debug
```
Le code généré (`*.g.dart`) est versionné. La CI vérifie qu'il est à jour (`.github/workflows/mobile.yml`).

## Limites
- Pas de synchro en arrière-plan (application fermée) : uniquement au premier plan.
- Une opération « à revoir » n'est pas rejouable depuis l'écran : elle est consultable seulement.
- Le décalage d'horloge du téléphone n'est corrigé que vers le passé (date terrain ramenée à « maintenant »).
- HTTP en clair : Android et iOS le bloquent par défaut. Le serveur doit être en HTTPS (certificat interne installé sur les terminaux).
