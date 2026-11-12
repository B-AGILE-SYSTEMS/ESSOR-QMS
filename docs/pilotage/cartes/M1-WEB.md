# Carte M1-WEB
Branche `m1/web` depuis `integration/m1`. Périmètre : `web/`.
Lire : contrat `docs/pilotage/cartes/M1-contrat.md`, maquettes `docs/ux/maquettes-v2.html` (écrans `#mes-actions`, `#nc-liste`, `#nc-declarer` … `#nc-cloturer`), code existant de `web/`.
1. « Mes actions » branché sur `/api/my-tasks` et `/api/dashboard/summary` (fin des données d'exemple).
2. Non-conformités : liste filtrable/paginée ; déclaration ; fiche NC avec barre d'étapes, onglets (Description, Analyse 5 pourquoi + 6M dans `data`, Actions, Historique), boutons de transition issus de `availableTransitions` (désactivés avec la liste de ce qui manque), ajout et suivi des actions, sélection d'un responsable.
3. Bouton « + Déclarer une NC » fonctionnel partout.
4. Panneau notifications : compteur non lues, liste, marquer lu, temps réel par SSE.
5. Paramétrage : écran Workflows (lecture du workflow NC en diagramme simple états/transitions ; édition JSON avec validation côté serveur), écran Règles de notification.
6. Tests unitaires des services et composants clés ; un test e2e Playwright du parcours NC complet contre une API simulée.
Prouve : lint, tests, build verts. Pousse `m1/web`.
