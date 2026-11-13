# Carte M3-BACK
Branche `m3/back` depuis `integration/m3`. Périmètre : backend/, deploy/.
Partie serveur du contrat M3 : référentiels + import (Apache POI pour xlsx), types AUDIT et DOCUMENT par migration, grille, réponses, NC automatiques, rapport PDF, versions et accusés de lecture, alerte de revue. RLS FORCE sur toutes les nouvelles tables. Jeu de démo ISO 9001 / ISO 45001 (profil demo) en formulations propres, sans copier la norme.
Tests d'intégration : import (erreurs ligne par ligne, simulation), parcours audit complet jusqu'au PDF (vérifier qu'il s'ouvre et contient la synthèse), NC créée par un écart, cycle documentaire et une seule version en vigueur, accusés de lecture, isolation inter-tenant. `mvn -B verify` vert.
