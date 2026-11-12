# ESSOR QMS — contexte global

Plateforme QHSE paramétrable (industrie, logistique, services, cabinets qualité).
Vision complète : https://claude.ai/artifact/MmJ8xtbaN8bSpJsD37gAbx

## Règles non négociables
- **On-premise** : un serveur Linux, Docker Compose, aucun service cloud requis, fonctionne en réseau fermé.
- Stack : Java 21, Spring Boot 3 (Spring Modulith), PostgreSQL 16, Angular 18, Flutter, Keycloak auto-hébergé (BFF).
- Multi-tenant : `tenant_id` sur chaque table métier + RLS PostgreSQL. Mode cabinet = un utilisateur rattaché à plusieurs tenants.
- Moteur générique : fiches + workflow paramétrable (automate d'états JSON maison), pas de module codé par type.
- Référentiels/normes = données importées, jamais du code.
- Mobile hors ligne d'abord (SQLite locale + file de synchro).
- Sécurité : OIDC, journal d'audit, aucune donnée personnelle dans les logs.

## Arborescence
- `backend/` API Spring Boot
- `web/` Angular 18
- `mobile/` Flutter
- `deploy/` Docker Compose, Nginx, Keycloak realm, sauvegardes
- `.github/workflows/` CI
