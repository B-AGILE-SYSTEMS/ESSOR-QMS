# Contrat M3b — Contrôles des flux et traçabilité
Référence métier validée : docs/projet/perimetre-tracabilite.md.
Démo : un lot fournisseur reçu est contrôlé (échantillonnage) puis libéré ; il est consommé par un OF dont le lot fini est contrôlé (en-cours avec carte de contrôle, sortie) puis expédié à 2 clients ; le fournisseur signale un défaut : depuis son lot, en < 1 min, on obtient lots finis et clients touchés, on bloque le stock restant (webhook vers un WMS simulé) et on exporte le dossier de rappel PDF.

## Données de base (import CSV/xlsx comme M3, ou API)
- `article` (code, désignation, famille, unité, suivi lot|série, DLC oui/non), `partner` (code, nom, type fournisseur|client), table de nomenclature simple facultative.
- `POST /api/master-data/{articles|partners}/import` (même format de rapport que l'import M3, `?dryRun=true`).

## Lots et statut qualité
- `lot` : article, numéro, fournisseur + n° lot fournisseur (si acheté), quantité, unité, DLC, site, `qualityStatus` = EN_ATTENTE|LIBERE|QUARANTAINE|DEROGATION|REFUSE|DETRUIT, historique des changements (qui, quand, pourquoi).
- `POST /api/lots` ; `GET /api/lots?q=&status=&articleId=` ; `GET /api/lots/{id}` ; `POST /api/lots/{id}/status` `{status, reason}` (DEROGATION exige un motif et le rôle QUALITY_MANAGER ; une NC peut être liée).
- Liens de généalogie : `POST /api/lots/{id}/links` `{kind:"consumed_by|shipped_to|split_into", targetLotId?, partnerId?, quantity, reference}` (OF, BL, SSCC dans `reference`).
- `GET /api/lots/{id}/trace?direction=up|down&depth=` → arbre `{lot, children:[{link, node}]}` ; `GET /api/lots/{id}/recall` → `{lotsImpactes:[...], clients:[{partner, quantite, references}], quantites:{enStock, enCours, expedie}}` ; `GET /api/lots/{id}/recall.pdf` (dossier de rappel, logo).
- Événements sortants : `POST` webhook configurable (paramétrage connecteurs, URL + secret HMAC) à chaque changement de statut de lot : `{event:"lot.status_changed", lotId, numero, article, status, reason, at}`.

## Plans et contrôles
- `control_plan` : point de flux (RECEPTION|STOCKAGE|PREPARATION|PRODUCTION_ENTREE|PRODUCTION_ENCOURS|PRODUCTION_SORTIE|EXPEDITION), cible (article, famille, fournisseur ou client), caractéristiques `[{code, libelle, type:"mesure|attribut|document", nominal, tolMin, tolMax, unite, instrumentRequis, criticite:"critique|majeure|mineure"}]`, échantillonnage `{mode:"fixe|iso2859", taille|nqa, niveau}`, fréquence (en-cours : toutes les N pièces ou X min). Versionné.
- `GET/POST/PUT /api/control-plans`.
- Échantillonnage ISO 2859-1 : niveau général II, simple, normal ; lettres-codes et tailles d'échantillon + Ac/Re pour NQA courants (0,65 ; 1,0 ; 1,5 ; 2,5 ; 4,0 ; 6,5) calculés côté serveur (table codée, testée).
- Contrôle (`inspection`) : `POST /api/inspections` `{lotId, controlPlanId, point}` → crée l'inspection avec la taille d'échantillon calculée ; `PUT /api/inspections/{id}/results` `[{characteristic, values:[...], ok}]` (Idempotency-Key, mobile) ; `POST /api/inspections/{id}/decision` `{decision:"LIBERE|QUARANTAINE|DEROGATION|REFUSE", comment}` → met à jour le statut du lot, crée une NC si REFUSE ou DEROGATION (liée au lot et au fournisseur).
- En-cours : `GET /api/inspections/spc?controlPlanId=&characteristic=` → points (moyenne, étendue par sous-groupe), limites calculées (X̄/R), Cp/Cpk, alertes (point hors limites, 7 points d'un même côté).
- Chaque résultat garde : auteur, date, site, version du plan, instrument utilisé (champ libre en M3b, lien métrologie en M4).

## Mobile
- Scan d'un code lot (GS1-128 : AI 10 lot, 17 DLC, 00 SSCC ; ou QR `essor://lot/<id>`), écran contrôle guidé par le plan (une caractéristique par écran, saisie des valeurs, verdict automatique selon tolérances), décision proposée, photos ; hors ligne via la file M2.

## Web
- Menu Terrain → « Contrôles » (à faire, en cours, historique) ; Ressources → « Lots & traçabilité » : fiche lot (statut, historique, contrôles, généalogie en arbre ascendant/descendant), bouton « Simuler un rappel » (liste, quantités, PDF, blocage du stock restant avec confirmation dans la page).
- Paramétrage : plans de contrôle (éditeur de caractéristiques), connecteur webhook, import articles/partenaires.
- Carte de contrôle (SVG) avec limites et points hors limites.
