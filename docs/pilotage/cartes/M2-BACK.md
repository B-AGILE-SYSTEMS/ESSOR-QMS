# Carte M2-BACK
Branche `m2/back` depuis `integration/m2`. Périmètre : backend/, deploy/.
Implémenter la partie serveur du contrat M2 (type INCIDENT par migration, idempotence, sync/changes, pièces jointes, clientId/occurredAt). RLS FORCE sur les nouvelles tables. Tests d'intégration : renvoi idempotent sans doublon, pièce jointe refusée si le contenu ne correspond pas au type, isolation inter-tenant des pièces jointes, sync/changes. `mvn -B verify` vert.
