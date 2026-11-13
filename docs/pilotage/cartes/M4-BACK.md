# Carte M4-BACK
Branche `m4/back` depuis `integration/m4`. Périmètre : backend/, deploy/, docs/api-exemples/.
Partie serveur du contrat M4 (modules Modulith `risks`, `regulatory`, `metrology`, `partners`). RLS FORCE. Jeu de démo complet (profil demo).
En tête : les tests ne doivent plus réécrire `docs/api-exemples/` à chaque exécution ; ils écrivent dans `target/api-exemples/` et un test compare la STRUCTURE (clés et types, pas les valeurs) avec `docs/api-exemples/` ; une commande documentée (`mvn -Papi-exemples verify` ou équivalent) régénère les fichiers volontairement. Ajouter les exemples M4.
Tests : cotation et seuils, matrice, DUER PDF, évaluation réglementaire → action, taux de conformité, verdict d'étalonnage serveur (EMT), blocage + NC + effet instrument (contrôles et lots depuis le dernier étalonnage conforme), 409 instrument bloqué dans un contrôle, scorecard avec indicateurs automatiques, campagne, isolation tenant. `mvn -B verify` vert.
