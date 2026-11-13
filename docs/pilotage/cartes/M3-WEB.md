# Carte M3-WEB
Branche `m3/web` depuis `integration/m3`. Périmètre : web/.
Partie web du contrat M3, conforme à docs/ux/maquettes-v2.html (réutiliser le composant de fiche générique). Mock e2e aligné sur le contrat ; e2e : import d'un référentiel, audit jusqu'au rapport, cycle d'un document avec accusé de lecture.
En plus : migrer vers le builder `@angular/build:application` (esbuild), Webpack étant déprécié en Angular 22 ; aucun changement visible (captures avant/après).
Lint, tests (couverture ≥ 60 %), e2e, build verts.
