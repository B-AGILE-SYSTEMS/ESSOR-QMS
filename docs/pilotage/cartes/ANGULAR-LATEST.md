# Carte ANGULAR-LATEST — Passage à la dernière version d'Angular (décision Driss — Métier, 13/11/2026)
Branche `tech/angular-latest` depuis `main`. Périmètre : `web/`, `.github/workflows/web.yml`.
Cible : version `latest` publiée sur npm (22.2.x au 13/11/2026), en passant par chaque version majeure (19 → 20 → 21 → 22) avec `ng update` et ses migrations automatiques.
Mêmes critères de stabilité que la carte ANGULAR19 :
- aucune différence visuelle (captures avant/après Mes actions, fiche NC, fiche incident) ;
- lint, Karma (couverture ≥ 60 %), e2e Playwright, build verts ;
- `npm audit --omit=dev --audit-level=high` à 0 ; remettre le job d'audit de la CI en bloquant.
Si une étape ou une dépendance bloque la stabilité, s'arrêter à la dernière version majeure stable et rapporter.
