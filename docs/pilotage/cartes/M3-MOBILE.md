# Carte M3-MOBILE
Branche `m3/mobile` depuis `integration/m3`. Périmètre : mobile/.
Partie mobile du contrat M3 : « Mes audits », exécution question par question hors ligne (Conforme / Écart mineur / Écart majeur / Observation / N.A., commentaire, photos), envoi par la file M2 avec Idempotency-Key ; documents en vigueur téléchargés pour lecture hors ligne et accusé de lecture. Écrans conformes à la maquette v2. Tests : exécution hors ligne puis synchro, reprise après coupure, accusé de lecture différé. `flutter analyze` et `flutter test` verts.
