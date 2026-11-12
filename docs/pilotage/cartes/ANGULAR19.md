# Carte ANGULAR19 — Migration du web vers Angular 19 (décision Driss — Métier, 12/11/2026 : « ok si stable »)
Branche `tech/angular19` depuis `main`. Périmètre : `web/`, `.github/workflows/web.yml`.
1. `ng update @angular/core@19 @angular/cli@19` puis dernier correctif 19.2.x stable (≥ 19.2.26). Appliquer les migrations automatiques ; garder composants standalone, signals, SCSS. Dépendances tierces compatibles 19 ; retirer les overrides npm devenus inutiles.
2. « Stable » = aucun changement fonctionnel visible : lint, Karma (couverture ≥ 60 %), e2e Playwright (tous les parcours existants), build verts ; captures avant/après de Mes actions, fiche NC et fiche incident identiques à l'œil.
3. `npm audit --omit=dev --audit-level=high` : 0 vulnérabilité haute ou critique. Remettre le job d'audit de la CI en bloquant (retirer `continue-on-error`).
4. Mettre à jour web/README.md.
Si une dépendance bloque la stabilité, s'arrêter et rapporter plutôt que contourner.
