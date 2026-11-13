# Contrat M4 — Risques, veille réglementaire, métrologie, clients et fournisseurs
Démo : (1) un instrument est trouvé hors tolérance à l'étalonnage → il est bloqué, et la liste des contrôles et lots mesurés avec lui depuis son dernier étalonnage valide sort en un clic, avec réévaluation des lots ; (2) la cartographie des risques d'un site montre un risque critique dont l'action de maîtrise est une action M1 ; (3) un texte réglementaire applicable est évalué non conforme → plan d'action ; (4) l'évaluation trimestrielle d'un fournisseur se calcule (notes + indicateurs automatiques : NC, lots refusés, retards) et déclenche un plan de progrès.
Règles transverses : tout est paramétrable par données (grilles, échelles, critères), RLS FORCE, actions = fiches ACTION M1 rattachées, notifications M1, exemples JSON réels dans `docs/api-exemples/`.

## Risques et opportunités
- `risk_scale` paramétrable : dimensions (gravité, probabilité, maîtrise, détectabilité…), niveaux 1..n avec libellés, formule (produit des dimensions retenues), seuils de criticité (faible/moyen/élevé/critique) et couleurs.
- `risk` : type (`sst` DUER, `environnement` aspect/impact, `qualite`, `opportunite`), site, unité de travail / processus, danger ou aspect, situation, cotation brute et résiduelle, mesures de maîtrise existantes, actions (fiches ACTION), exigences liées (M3), date de réévaluation. Historique des cotations.
- API : `GET/POST/PUT /api/risks`, `GET /api/risks/matrix?siteId=&type=` (cases matrice avec nombre et liste), `GET/PUT /api/risk-scales`, `GET /api/risks/duer.pdf?siteId=` (document unique), import CSV/xlsx (rapport comme M3).

## Veille réglementaire
- `regulation` : référence (ex. « Loi 65-99 Code du travail, art. 281 »), titre, domaine (SST, environnement, qualité, produit…), juridiction (Maroc, France, UE…), date de publication et d'application, résumé maison, lien source, statut (en vigueur, abrogé, à venir).
- `regulation_requirement` : exigences applicables extraites (texte maison), applicabilité par site (oui/non + justification).
- Évaluation périodique : `POST /api/regulations/{id}/evaluations` `{siteId, result:"conforme|non_conforme|partiel|na", evidence, comment}` ; non conforme → action M1 ; échéance de réévaluation et alerte.
- `GET /api/regulations?domain=&siteId=&status=`, tableau de conformité réglementaire par site (taux — c'est le KPI « conformité réglementaire » du tableau de bord), import CSV/xlsx.
- Jeu de démo : une dizaine de textes marocains et français réels par leur référence et titre, résumés rédigés par nous.

## Métrologie
- `instrument` : code (PAC-031), désignation, type, site, emplacement, numéro de série, plage, résolution, EMT (erreur maximale tolérée), périodicité d'étalonnage, prestataire, statut (`en_service|bloque|en_etalonnage|reforme`), QR `essor://instrument/<id>`.
- `calibration` : date, prestataire ou interne, certificat (pièce jointe), points de mesure `[{nominal, mesure, ecart}]`, verdict conforme/non conforme (calculé côté serveur avec l'EMT), prochaine échéance.
- Non conforme → statut `bloque` automatique, notification, NC liée, et **effet instrument** : `GET /api/instruments/{id}/impact?since=` → contrôles (M3b `inspection` dont un résultat référence l'instrument) et lots concernés depuis le dernier étalonnage conforme ; action « mettre ces lots en quarantaine pour réévaluation » (passe par l'API lots M3b, webhooks).
- Les résultats de contrôle M3b gagnent `instrumentId` (le champ libre devient un lien ; un instrument bloqué ou échu ne peut pas être choisi → 409).
- Alertes d'échéance J-15 et échu (tâche planifiée M1), KPI « étalonnages sous 15 jours ».
- `GET/POST/PUT /api/instruments`, `POST /api/instruments/{id}/calibrations`, import CSV/xlsx.

## Évaluation clients et fournisseurs
- `evaluation_grid` paramétrable : critères pondérés, échelle, seuils (A/B/C ou agréé/sous surveillance/refusé).
- `partner_evaluation` : partenaire (M3b `partner`), période, notes par critère, indicateurs automatiques calculés sur la période (fournisseur : nombre de NC liées, % lots refusés, lots en dérogation ; client : réclamations), note globale, classe, commentaire ; classe C/refusé → plan de progrès (fiche ACTION).
- Satisfaction client : questionnaire simple (critères notés) saisi en interne.
- `GET/POST /api/partners/{id}/evaluations`, `GET /api/partners/{id}/scorecard` (historique des notes et indicateurs), campagne : `POST /api/evaluation-campaigns` `{grid, partnerIds, period}` crée les évaluations à faire.

## Web
- Pilotage → Risques : matrice interactive (clic sur une case → liste), fiche risque, DUER PDF. Pilotage → Veille réglementaire : registre, fiche texte, évaluation par site, taux. Ressources → Équipements (instruments) : parc, fiche avec historique d'étalonnage, saisie d'étalonnage avec verdict, écran « effet instrument ». Ressources → Clients & fournisseurs : liste, scorecard, campagne d'évaluation. Paramétrage : échelles de risque, grilles d'évaluation. Tableau de bord : KPI conformité réglementaire et étalonnages réels.
## Mobile
- Scan d'un instrument (QR) : fiche, statut, échéance ; refus visible si bloqué/échu. Dans le contrôle M3b, choix de l'instrument par scan ou liste (seuls les instruments en service et non échus).
