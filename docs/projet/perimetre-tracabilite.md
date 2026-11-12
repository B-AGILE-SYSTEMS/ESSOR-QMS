# Périmètre — Contrôles des flux et traçabilité complète (V1 exploitant)

Statut : **validé [Driss — Métier]** · 12/11/2026

## 1. Principe

ESSOR QMS ne remplace ni l'ERP, ni le WMS, ni le MES. Ces systèmes gardent les mouvements physiques (quantités, emplacements, ordres de fabrication). ESSOR QMS porte **la qualité de chaque flux** :
- ce qu'on contrôle, à quel point du flux, comment et avec quel instrument (plan de contrôle) ;
- le résultat et la décision (accepté, sous dérogation, refusé, bloqué) ;
- la **généalogie des lots**, reconstituée à partir des événements reçus des systèmes ;
- le lien avec le reste du QHSE : non-conformités, fournisseurs, instruments, documents, risques.

Sans ERP/WMS/MES connecté, tout se saisit dans ESSOR QMS (web ou mobile, scan code-barres/QR) : un petit site doit pouvoir fonctionner seul.

## 2. Points de contrôle couverts

| Flux | Point de contrôle | Contrôles typiques | Décision et effet |
|---|---|---|---|
| **Réception** | Arrivée camion | Conformité documentaire (BL, certificat matière, CoA), état du véhicule, température (chaîne du froid), scellés | Accepté / réserve transporteur / refus de livraison |
| | Contrôle d'entrée du lot | Échantillonnage ISO 2859-1 (NQA), mesures dimensionnelles, aspect, étiquetage, DLC/DLUO restante | Libéré / quarantaine / refusé. Effet : statut de stock poussé au WMS/ERP, notation fournisseur, NC fournisseur auto |
| **Stockage** | Inventaire qualité et rondes | Conditions (température, humidité), péremption, FEFO/FIFO, intégrité des emballages, séparation des produits dangereux | Blocage du lot ou de l'emplacement, NC, alerte avant péremption |
| | Contrôle périodique / requalification | Ré-analyse avant échéance, contrôle après incident (chute, rupture du froid) | Prolongation / déclassement / destruction |
| **Préparation** | Prélèvement et colisage | Bon article, bon lot, quantité, état, étiquette client, respect FEFO | Correction avant expédition, NC préparation |
| | Contrôle avant expédition | Contrôle final par échantillon, photos du chargement, plombage, documents (certificat de conformité généré) | Expédiable / bloqué |
| **Production : entrée** | Libération de l'OF | Matières libérées uniquement (lots en quarantaine interdits), documents en vigueur, instruments étalonnés, opérateur habilité, réglages machine | Démarrage autorisé / bloqué |
| | Premier article (FAI) | Contrôle complet de la première pièce | Validation du lancement |
| **Production : en-cours** | Contrôles en cours de fabrication | Fréquence par plan (toutes les N pièces / X min), cartes de contrôle SPC (moyenne/étendue, Cp/Cpk), autocontrôle opérateur | Alerte de dérive, arrêt, tri, NC process |
| | Changement de série, d'outil, de matière | Contrôle de redémarrage | Validation du redémarrage |
| **Production : sortie** | Contrôle final / libération du lot fini | Mesures finales, essais, aspect, marquage, rapprochement quantités (bonnes, rebuts, retouches) | Libéré / quarantaine / dérogation client / rebut |
| | Rebuts et retouches | Motif, quantité, coût (MAD) | Coût de non-qualité, Pareto des causes |
| **Expédition et après-vente** | Retour client, réclamation | Lien avec le lot expédié | Traçabilité descendante, 8D |

## 3. Traçabilité complète

**Objet central : le lot** (ou le numéro de série pour les produits unitaires). Chaque lot porte un statut qualité unique : *en attente de contrôle → libéré / quarantaine / sous dérogation / refusé / détruit*.

**Généalogie.** Chaque transformation est enregistrée comme un lien lot consommé → lot produit, avec la quantité :
- réception : fournisseur + n° de lot fournisseur + BL → lot interne ;
- production : lots matières consommés par l'OF → lot fini (et sous-ensembles) ;
- préparation et expédition : lot → colis/palette (SSCC) → commande → client.

**Pour chaque contrôle on garde :** qui, quand, où (site, ligne, quai), le plan de contrôle et sa version, l'instrument utilisé et son état d'étalonnage le jour J, les valeurs mesurées, les photos et la décision. Tout est horodaté, signé et non modifiable (journal d'audit).

**Ce que l'utilisateur obtient :**
- **Traçabilité ascendante :** depuis un lot fini ou une réclamation client, on remonte en un clic aux lots matières, aux fournisseurs, aux OF, aux contrôles et aux opérateurs.
- **Traçabilité descendante :** depuis un lot fournisseur suspect, on retrouve tous les lots finis et tous les clients livrés.
- **Simulation de rappel/retrait :** périmètre impacté, quantités en stock / en cours / expédiées, liste des clients, export du dossier. Objectif : dossier prêt en moins de 4 heures.
- **Effet instrument :** si un instrument est trouvé hors tolérance à l'étalonnage, on liste tous les contrôles faits avec lui depuis le dernier étalonnage valide, et donc les lots à réévaluer.
- **Dossier de lot** en PDF (certificat de conformité, relevés de contrôle), avec le logo de l'exploitant.

## 4. Paramétrage (sans développement)

- **Plans de contrôle** par article, famille, fournisseur ou client : caractéristiques, tolérances, méthode, instrument requis, fréquence, règle d'échantillonnage, criticité (critique, majeure, mineure).
- **Règles de décision** : seuils, dérogation qui exige une validation (via le moteur de workflow), contrôle renforcé ou allégé selon l'historique fournisseur (règles de dynamisation ISO 2859-1).
- **Identification** : formats de code-barres/QR, GS1-128 (lot, DLC, SSCC), étiquettes de statut imprimables (quarantaine, libéré).

## 5. Intégration

| Système | Entrant dans ESSOR QMS | Sortant vers le système |
|---|---|---|
| ERP | Articles, fournisseurs, clients, commandes, réceptions attendues, OF, nomenclatures | Statut qualité des lots, coût de non-qualité |
| WMS | Réceptions, mouvements, emplacements, préparations, expéditions (lot → SSCC → commande) | Blocage / déblocage de lot ou d'emplacement |
| MES | Lancements d'OF, consommations de lots, production déclarée, rebuts, mesures machines | Autorisation de démarrage, arrêt sur dérive |
| TMS | Transporteur, températures de transport, preuves de livraison | Réserves transporteur |
| LIMS / instruments connectés (option) | Résultats d'analyses, mesures automatiques | — |

Les échanges passent par l'API REST et les webhooks (cf. contrat). L'import Excel/CSV couvre le démarrage sans connecteur.

## 6. Hors périmètre V1

Gestion des stocks et des emplacements (reste au WMS), ordonnancement de production, pilotage machine en temps réel, étiquetage logistique complet. Le SPC sur flux automatique à haute fréquence (plus d'une mesure par seconde) est à étudier en V2.

## 7. Place dans le planning

Proposition : un milestone dédié **M3b « Contrôles des flux et traçabilité »** après M3 (audits, GED, référentiels), parce qu'il s'appuie sur le moteur de fiches (M1), le mobile hors ligne (M2) et la métrologie.

1. Réception, contrôle d'entrée, statut des lots, quarantaine.
2. Production entrée, en-cours et sortie, SPC simple.
3. Préparation et expédition, généalogie, traçabilité ascendante et descendante, simulation de rappel.

Démo de fin de milestone : un lot fournisseur déclaré défectueux → en moins d'une minute, liste des lots finis et des clients touchés, blocage du stock restant poussé au WMS simulé.
