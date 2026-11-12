# Carte M1-BACK
Branche `m1/back` depuis `integration/m1`. Périmètre : `backend/`, `deploy/`, `.github/workflows/backend.yml`.
Lire : contrat `docs/pilotage/cartes/M1-contrat.md` (strict), contrat M0, code existant de `backend/`.
1. Nouveau module Modulith `records` (moteur générique + workflow), `notifications`. Tout tenant-scopé avec RLS FORCE comme en M0. Migrations Flyway V3+.
2. Implémenter tout le contrat M1. Pagination bornée (size ≤ 100). Validation des entrées.
3. Test d'intégration **Keycloak réel** : conteneur Keycloak (Testcontainers, image `quay.io/keycloak/keycloak:26.0.7` ou équivalent Docker Hub si quay.io inaccessible) qui importe `deploy/keycloak/realm-essor.json` et joue le parcours de connexion BFF (code d'autorisation + PKCE) jusqu'à `GET /api/me`. C'est la dette relevée à la vérification M0.
4. Tests : parcours NC complet de bout en bout par l'API (création → clôture, refus des transitions interdites, 409 si actions non faites), isolation RLS des fiches, notifications émises, SSE.
5. Données de démo (profil `demo` uniquement) : 6 NC à différents statuts et leurs actions, sur les sites DEMO.
Prouve : `mvn -B verify` vert. Pousse `m1/back`.
