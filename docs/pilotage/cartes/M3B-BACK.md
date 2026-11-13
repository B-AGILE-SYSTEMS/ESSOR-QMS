# Carte M3B-BACK
Branche `m3b/back` depuis `integration/m3b`. Périmètre : backend/, deploy/.
Partie serveur du contrat M3b (module Modulith `traceability`). RLS FORCE partout. Jeu de démo (profil demo) : 6 articles, 3 fournisseurs, 4 clients, plans de contrôle réception et production, un scénario complet (lot fournisseur → OF → lot fini → 2 expéditions). Webhook : envoi asynchrone avec reprise (3 essais), signature HMAC-SHA256, journal des envois.
Tests : table ISO 2859-1 (cas connus), calcul X̄/R et Cpk, généalogie montante/descendante sur plusieurs niveaux, rappel (quantités, clients) en < 1 s sur 10 000 liens, décision → statut → NC, webhook signé, isolation tenant. `mvn -B verify` vert.
