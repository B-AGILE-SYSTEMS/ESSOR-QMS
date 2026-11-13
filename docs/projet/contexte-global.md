# ESSOR QMS — contexte global

Plateforme QHSE paramétrable pour l'exploitant (industrie, logistique, services). V1 sans mode cabinet.
Vision complète : https://claude.ai/artifact/MmJ8xtbaN8bSpJsD37gAbx

## Règles non négociables
- **On-premise** : un serveur Linux, Docker Compose, aucun service cloud requis, fonctionne en réseau fermé.
- Stack : Java 21, Spring Boot 3 (Spring Modulith), PostgreSQL 16, Angular 22, Flutter, Keycloak auto-hébergé (BFF).
- Multi-tenant : `tenant_id` sur chaque table métier + RLS PostgreSQL. Un utilisateur = un tenant (l'exploitant), rattaché à un ou plusieurs sites. Pas de mode cabinet en V1.
- Apparence paramétrable par l'exploitant : logo, couleur principale, thème (clair, sombre, haute lisibilité = `terrain`). Maquettes de référence : https://claude.ai/artifact/JUqcWM4NBVFgY1M4fPDz4f
- Moteur générique : fiches + workflow paramétrable (automate d'états JSON maison), pas de module codé par type.
- Référentiels/normes = données importées, jamais du code.
- Mobile hors ligne d'abord (SQLite locale + file de synchro).
- Sécurité : OIDC, journal d'audit, aucune donnée personnelle dans les logs.

## Arborescence
- `backend/` API Spring Boot
- `web/` Angular 22
- `mobile/` Flutter
- `deploy/` Docker Compose, Nginx, Keycloak realm, sauvegardes
- `.github/workflows/` CI
