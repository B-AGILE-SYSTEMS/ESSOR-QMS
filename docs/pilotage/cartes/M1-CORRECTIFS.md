# Carte M1-CORRECTIFS (avant M2)
Branche d'intégration : `integration/m1-correctifs` (depuis main). Une branche par agent.

## A — back + web (`m1c/back-web`, périmètre backend/, web/, deploy/)
1. Dépendances : 69 alertes Dependabot sur le dépôt (1 critique), détail inaccessible depuis l'outil. Mettre à jour vers les dernières versions correctives compatibles :
   - Spring Boot 3.3.x → dernière 3.x supportée (et Testcontainers, Spring Modulith alignés) ;
   - Angular 18 → dernier correctif 18.x (rester en 18, exigence client) et toutes les dépendances npm ; `npm audit` doit donner 0 vulnérabilité haute ou critique en production (`npm audit --omit=dev`), et le moins possible en dev ;
   - images Docker du compose et des Dockerfile (postgres, keycloak, nginx, temurin) au dernier correctif.
   Lister les versions avant et après dans le rapport.
2. Fiche : « Déclarée par » et l'historique affichent le nom de la personne, pas l'identifiant (API : ajouter `createdByName`, `author` en nom lisible).
3. Couverture web : brancher karma-coverage (rapport lcov + texte), seuil minimal 60 % lignes, et l'afficher dans la CI.
4. Ajouter un job de CI `npm audit --omit=dev --audit-level=high` (web) et le plugin OWASP dependency-check ou équivalent (backend) en non bloquant si la base NVD n'est pas joignable.
Prouver : `mvn -B verify`, lint, tests, e2e, build verts.

## B — mobile (`m1c/mobile`, périmètre mobile/)
1. Dépendances pub au dernier correctif compatible ; `flutter pub outdated` dans le rapport.
2. Hors ligne au démarrage : mettre en cache le profil (`/api/me`) et l'apparence ; sans réseau, l'app s'ouvre sur l'accueil avec le dernier profil connu et l'indicateur hors ligne, la session n'est renvoyée vers la connexion que si le jeton est révoqué (401 avec réseau).
Prouver : `flutter analyze`, `flutter test` verts.
