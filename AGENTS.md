# Règles de travail

- Une tâche = une branche `m<N>/<id-carte>`, intégrée dans la branche du milestone `integration/m<N>`.
- Fin de milestone : une fois la vérification du milestone validée (tests et démo verts), le coordinateur fusionne `integration/m<N>` dans `main` sans attendre de validation humaine (accord de Driss du 12/11/2026).
- Ne sors jamais du périmètre de ta carte (liste de dossiers autorisés).
- Définition de terminé : build vert, tests verts, critères d'acceptation de la carte démontrés, README du module à jour.
- Interdits : secrets en dur, dépendance à un service cloud, désactiver un test, modifier un dossier hors périmètre.
- Vérification indépendante : UNE seule par milestone, sur `integration/m<N>`, par un agent qui n'a pas produit le code (build, tests, tests de bout en bout, contrat). Pas de vérification par tâche, pas de contre-vérification de vérification sauf demande de Driss.
- Pendant le milestone, chaque agent prouve lui-même que sa tâche est terminée (build et tests verts).
- Outillage : sélection Everything Claude Code dans `.claude/` (voir `.claude/ECC.md`). Les règles du projet priment.
