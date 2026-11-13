# Contrat M3 — Référentiels, audits, gestion documentaire

Démo : la norme ISO 45001 est importée (fichier Excel) ; un audit interne de l'Entrepôt Nord est planifié avec une grille générée depuis ses exigences ; l'auditeur l'exécute sur mobile (hors ligne possible), un écart crée automatiquement une NC liée à l'exigence ; le rapport d'audit PDF est produit avec le logo ; une procédure passe rédaction → vérification → approbation → diffusion, les destinataires accusent lecture.

## Référentiels (données, jamais du code)
- `standard` (code, titre, version, ex. ISO45001:2018) → arborescence `requirement` (code « 8.1.2 », titre, texte, parent, ordre, `auditable` bool).
- Import : `POST /api/standards/import` (multipart xlsx ou csv ; colonnes `code;titre;texte;parent;auditable`) → rapport `{created, updated, errors:[{line, message}]}` ; mode simulation `?dryRun=true`. Modèle téléchargeable `GET /api/standards/import/template`.
- `GET /api/standards`, `GET /api/standards/{id}/requirements` (arbre), `GET /api/requirements/{id}` avec ses liens (documents, NC, constats d'audit).
- Lien d'une fiche (NC, incident) ou d'un document à des exigences : `PUT /api/records/{id}/requirements` `[requirementId]`.
- Livrer en jeu de démo : ISO 9001:2015 et ISO 45001:2018 (titres des chapitres et sous-chapitres, textes résumés en une phrase rédigée par nous, sans recopier le texte de la norme).

## Audits (type de fiche `AUDIT`, moteur M1)
- Champs : site, référentiel(s), périmètre, auditeur(s), audités, date prévue. Workflow : PLANIFIE → EN_COURS → RAPPORT → CLOTURE.
- Grille : `POST /api/audits/{id}/checklist/generate` `{standardIds, requirementIds?}` → questions (une par exigence auditable), modifiables.
- Réponse : `PUT /api/audits/{id}/answers/{questionId}` `{result:"conforme|ecart_mineur|ecart_majeur|observation|na", comment, evidence}` + pièces jointes (API M2) ; en-tête `Idempotency-Key` accepté (mobile).
- Un écart crée une NC liée (exigence, audit, site) à la clôture de la question ou en lot à `RAPPORT`.
- `GET /api/audits/{id}/report.pdf` : rapport PDF (logo et couleur du tenant, synthèse, taux de conformité par chapitre, constats, NC créées). Génération côté serveur (OpenPDF ou équivalent libre, aucune dépendance cloud).
- Programme : `GET /api/audits?year=` (calendrier annuel).

## Gestion documentaire (type de fiche `DOCUMENT`)
- Document = code (PR-QHSE-07), titre, type (procédure, instruction, formulaire, enregistrement), processus, exigences liées, destinataires (rôles et/ou sites).
- Versions : `POST /api/documents/{id}/versions` (fichier PDF/DOCX/XLSX ≤ 20 Mo + note de version) ; une seule version « en vigueur » ; historique complet ; les anciennes restent consultables, marquées « périmée ».
- Workflow par version : REDACTION → VERIFICATION → APPROBATION → DIFFUSE (→ PERIME) ; rôles vérificateur et approbateur paramétrables par document.
- À la diffusion : notification aux destinataires ; `POST /api/documents/{id}/versions/{v}/ack` (accusé de lecture) ; `GET .../acks` (qui a lu, qui reste).
- Révision périodique : date de revue, alerte J-30 (tâche planifiée M1).
- `GET /api/documents?status=&type=&q=`.

## Mobile
- Liste « Mes audits » et exécution question par question hors ligne (maquette v2), photos, synchro par la file M2.
- Consultation des documents en vigueur (téléchargés pour lecture hors ligne) et accusé de lecture.

## Web
- Paramétrage → Référentiels : import avec simulation et rapport d'erreurs, arbre des exigences, vue d'une exigence avec ses liens.
- Audits : programme annuel (calendrier), fiche audit (générer et éditer la grille, exécuter sur web, constats, NC créées), téléchargement du rapport PDF.
- Documents : liste, fiche (versions, workflow, destinataires, accusés de lecture), visionneuse PDF intégrée, téléversement d'une nouvelle version.
