# ESSOR QMS — Audit UX et Studio de personnalisation

Statut : proposition, à valider par [Driss — Métier] (périmètre, vocabulaire) et [Driss — Développement technique] (contrat d'API, découpage).
Sources : démo navigable `demo-m3`, parcourue avec Playwright à 1440 px et 820 px, plus des contrôles à 400 px. La démo `demo-m3b` n'était pas prête, et `demo-m3` ne contient ni lots, ni contrôles, ni données de base (ces routes renvoient vers Mes actions). Ces écrans sont donc audités sur le code source `web/src`. Captures : `ux-studio/captures/`.
Impact : 1 = bloque ou fait perdre une donnée, 2 = coût répété au quotidien, 3 = gêne.

## 1. Audit écran par écran

### Constats transverses (ils s'appliquent à tous les écrans)
| # | Imp. | Problème | Correction |
|---|---|---|---|
| T1 | 1 | Une URL interdite ou absente du build (lots, contrôles, plans de contrôle) **renvoie sans message** vers Mes actions (`roleGuard`, route `**`). | Page 403/404 explicite qui nomme le rôle requis et propose un retour (maquette, écran 3). |
| T2 | 1 | Les menus « Indicateurs », « Risques & conformité » et « Équipements » mènent à des pages « À venir » : ce sont des impasses dans la navigation principale. | Masquer ces menus tant que le module n'est pas livré (réglage Studio, masqué par défaut). |
| T3 | 1 | La couleur personnalisée accepte n'importe quel hexadécimal alors que `--acc-ink` reste fixé à `#fff`. Un jaune donne un bouton illisible (1,1:1), et l'anneau de focus `--acc` devient invisible. | Garde-fou de contraste calculé, texte du bouton choisi automatiquement (blanc ou noir), focus sur une couleur distincte et verrouillée. |
| T4 | 2 | Cibles trop petites : 31 éléments sur 34 font moins de 40 px de haut sur Mes actions en poste (liens de tableau, onglets, filtres). | Lignes de tableau cliquables en entier (44 px), onglets et puces à 44 px minimum. |
| T5 | 2 | Sous 900 px, l'en-tête occupe environ 300 px (recherche, double « Déclarer », notifications, déconnexion sur trois lignes) ; l'indice « Ctrl K » s'affiche aussi sur tablette. | En-tête sur une ligne avec la recherche en icône, un seul bouton « Déclarer », indice clavier masqué sur écran tactile. |
| T6 | 2 | Sous 900 px, les tableaux restent à 7 colonnes : la référence se coupe sur 3 lignes et 76 à 85 éléments débordent dans le conteneur. | Cartes de liste sous 900 px (maquette, écran 1). |
| T7 | 2 | Des codes techniques sont exposés : `DECLAREEINITIAL`, `RECORD_ASSIGNED`, `OPERATEUR`, `QUALITY_MANAGER`, `ESSOR_SMTP_*`. | Libellés métier partout ; les codes passent en infobulle pour l'administrateur. |
| T8 | 2 | « Se déconnecter » est un bouton texte de premier niveau, voisin de « Notifications », et part sans confirmation, même avec un brouillon en cours. | Placer la déconnexion dans le menu du profil et confirmer si un formulaire est modifié. |
| T9 | 3 | Le libellé du sélecteur « Site actif » est en bas du rail, hors de vue sur un portable de 768 px de haut. | Placer le site actif dans l'en-tête, à côté du nom de l'utilisateur. |

### Mes actions
1. (1) Les KPI (« En retard 1 ») ne sont pas cliquables : il faut filtrer à la main. → Chaque KPI ouvre la liste filtrée.
2. (2) Les compteurs des onglets « À faire (14) / En retard (1) / À valider (2) » ne reprennent pas la couleur d'alerte du retard. → Onglet « En retard » en couleur critique et sélectionné par défaut s'il n'est pas vide.
3. (2) Seul le titre est cliquable, avec une petite cible. → Toute la ligne est cliquable et accessible au clavier (`Entrée`).
4. (2) Le statut est une pastille sans action : il faut ouvrir la fiche pour avancer. → Action principale de l'étape (« Qualifier », « Valider ») proposée sur la ligne.
5. (2) Le KPI « Coût NC (mois) 48 300 MAD » n'a pas de comparaison. → Tendance par rapport au mois précédent.
6. (2) Pas d'état vide dédié : une liste vide affiche un tableau vide. → « Rien à faire aujourd'hui », lien vers les actions à venir.
7. (2) Le tri est imposé (échéance). → Tri par en-tête, mémorisé pour l'utilisateur.
8. (2) Pas de regroupement par fiche parente : action et NC sont mêlées. → Ligne indentée « Action de NC-2026-0006 ».
9. (3) Les dates seules (25/11/2026) ne disent pas l'urgence. → « dans 12 j » / « en retard de 1 j » à côté.
10. (3) Sur tablette, les 4 KPI s'empilent et poussent la liste hors de l'écran. → Bande de KPI compacte et défilable horizontalement.
11. (3) Pas de choix de widgets selon le rôle. → Studio, section Tableau de bord.

### Listes (NC, incidents, documents)
1. (1) La recherche vide renvoie « Aucune fiche. », sans rappel des filtres actifs ni bouton pour les effacer (capture `d-liste-vide.png`). → État vide explicite (maquette, écran 3).
2. (2) Les filtres ne s'appliquent qu'après un clic sur « Filtrer », alors qu'on s'attend à un effet immédiat. → Application immédiate, avec un délai de 300 ms pour la recherche.
3. (2) Les filtres ne sont pas conservés dans l'URL : retour arrière = filtres perdus. → Filtres dans les paramètres de requête.
4. (2) Pas de filtres enregistrés ni de vues partagées. → « Enregistrer cette vue » (Studio, section Listes).
5. (2) Colonnes fixes ; « Responsable » et « Échéance » valent souvent « — ». → Colonnes choisies par l'utilisateur ; « Sans responsable » mis en évidence.
6. (2) Pagination « ← Précédente / Suivante → » sans total ni taille de page. → « 1–25 sur 132 » et choix de 25, 50 ou 100.
7. (2) En-têtes non triables. → Tri sur chaque colonne, avec `aria-sort`.
8. (2) Le badge « MOBILE » est collé au titre (« allée BMOBILE » à la lecture d'écran). → Espace et `aria-label` « déclaré depuis le mobile ».
9. (2) Pas d'action groupée (assigner, exporter). → Cases à cocher et barre d'actions.
10. (2) Pas d'export CSV/XLSX depuis la liste. → Bouton « Exporter » qui respecte les filtres.
11. (3) Les deux boutons « + Déclarer » (en-tête et page) se concurrencent. → Un seul.
12. (3) La gravité est portée par le texte seul, sans repère visuel. → Bordure gauche colorée plus le texte (jamais la couleur seule).

### Fiche NC (et fiche générique)
1. (1) « Valider l'analyse » est désactivé, en contraste faible (opacité 0,55) ; le motif « Manque : Cause racine » est en 12 px rouge, sans lien vers le champ (capture `d-fiche-nc.png`). → Bouton actif ; le clic mène au champ manquant, la liste des manques est cliquable (maquette, écran 2).
2. (1) Deux boutons d'enregistrement (« Enregistrer l'analyse » puis la transition) : on peut valider sans avoir enregistré. → Enregistrement automatique du brouillon et transition qui enregistre d'abord.
3. (1) Un changement d'onglet après saisie se fait sans avertissement, au risque de perdre la saisie. → Garde « modifications non enregistrées ».
4. (2) Les champs 6M tronquent le texte (« Contrôle quotidien non form… »). → Zones de texte extensibles.
5. (2) Le stepper à 6 cases prend 60 px sans être cliquable ni expliquer l'étape suivante. → Stepper compact et phrase « Prochaine étape : Actions — par Salma B. ».
6. (2) Le commentaire « joint à la transition » est séparé du bouton par toute la page. → Il est dans la barre de décision collante.
7. (2) L'onglet « Actions (0) » ne propose pas de créer directement. → État vide « Aucune action — + Ajouter ».
8. (2) Le « Responsable : non désigné » n'est pas mis en avant sur une fiche qui en exige un. → Alerte en tête de fiche.
9. (2) Les transitions destructrices (« Rejeter », « Classer sans suite ») n'ont pas de confirmation ni de motif obligatoire. → Boîte de confirmation avec motif.
10. (2) L'historique est dans un onglet : pas de contexte récent. → Les 3 derniers événements visibles dans le panneau latéral.
11. (3) La fiche fait plus de 2 écrans sur tablette en portrait. → Panneau latéral replié sous 1024 px.
12. (3) Pas de raccourci clavier pour les transitions. → `Ctrl+Entrée` = action principale.

### Incidents
1. (1) Les champs « Blessés » et « Témoins » ne sont pas modifiables depuis la fiche après la déclaration mobile. → Édition en ligne tant que l'étape le permet.
2. (1) « Classer sans suite » est placé à côté de « Analyser », avec le même poids visuel. → Bouton secondaire et confirmation avec motif.
3. (2) Le libellé « Date terrain » est ambigu face à « Déclaré le ». → « Survenu le » et « Saisi le ».
4. (2) Pas de gravité potentielle et réelle distinctes (presqu'accident). → Deux champs, à ajouter par le Studio ou dans la charte.
5. (2) Photos en vignettes sans légende ni ordre. → Galerie légendée, glisser-déposer.
6. (2) Pas de lien direct pour créer une NC ou une action liée. → « Créer une action » dans la barre de décision.
7. (2) Les mêmes défauts que les listes NC (filtres, état vide, cartes mobile).
8. (3) Le badge « Déclaré depuis le mobile » est en capitales sans icône. → Puce discrète, avec l'appareil et la date de synchronisation.
9. (3) Pas d'indicateur d'urgence pour les incidents « critiques » non pris en charge depuis plus de 24 h. → Bandeau d'alerte.
10. (3) Le lieu (« Kénitra — allée B ») est du texte libre. → Lieu choisi dans les données de base, texte libre en complément.

### Audits
1. (2) Le programme est une grille de 12 mois, dont 9 sont vides sur un écran large ; aucun audit en retard n'est signalé. → Vue « liste » par défaut avec les retards en tête, la grille restant en option.
2. (2) Les flèches « ← → » n'ont pas de nom accessible explicite (« année précédente »). → `aria-label`.
3. (2) La grille d'audit répond question par question avec 5 boutons de 36 px : c'est lent sur tablette. → Boutons de 48 px et passage automatique à la question suivante.
4. (2) Un écart crée une NC sans aperçu de ce qui sera créé. → Aperçu et confirmation.
5. (2) Les scores par chapitre « §7 0 % » n'ont pas de légende. → « Chapitre 7 : 0 % conforme (1 écart majeur) ».
6. (2) La preuve n'est pas obligatoire pour un écart majeur. → Règle de formulaire (Studio).
7. (2) Le rapport PDF s'ouvre sans aperçu ni choix de contenu. → Aperçu et options.
8. (3) « Mes audits » est un bouton à côté de la navigation par année, alors que c'est un filtre. → Puce filtre.
9. (3) Le mode hors ligne n'est pas signalé en continu. → Bandeau d'état persistant.
10. (3) Pas de duplication d'audit d'une année sur l'autre. → « Reprogrammer ».

### Documents
1. (1) « J'ai lu et compris » est un bouton placé sous la liste des lecteurs, non affiché si l'utilisateur n'a pas encore ouvert le document. → Bandeau en tête de fiche, actif après ouverture du fichier.
2. (2) Les rôles s'affichent en codes (`OPERATEUR`, `QUALITY_MANAGER`). → Libellés et vocabulaire du Studio.
3. (2) La colonne « Revue » ne signale pas les échéances proches. → Couleur d'alerte à J-30, J-0.
4. (2) Le cycle à 5 étapes est affiché, même pour les formulaires simples. → Workflow allégé selon le type (déjà paramétrable : l'exposer).
5. (2) Pas de comparaison de versions. → Lien « Comparer v1 / v2 ».
6. (2) Les relances aux non-lecteurs se font à la main. → Bouton « Relancer les 3 personnes ».
7. (2) Pas d'aperçu du document dans la page. → Visionneuse PDF intégrée.
8. (3) La version affiche « — » pour les documents en rédaction. → « Brouillon v0.3 ».
9. (3) La recherche ne porte pas sur le contenu. → Recherche plein texte (backend).
10. (3) La liste de diffusion n'indique pas l'effectif ciblé avant diffusion. → « 37 personnes recevront… ».

### Lots et traçabilité (audit sur le code, écran absent de demo-m3)
1. (1) Le formulaire de réception se dévoile par un bouton bascule ; son bouton reste désactivé sans dire quel champ manque. → Liste des manques, comme pour la fiche NC.
2. (1) Si les données de base sont indisponibles, le formulaire disparaît avec un message générique. → Bandeau explicite avec un lien vers Données de base (ADMIN).
3. (2) La recherche ne se lance qu'après un clic sur le bouton « Rechercher ». → Recherche immédiate.
4. (2) Les libellés de la généalogie sont longs (« Descendante (où est-il allé ?) »). → Onglets « Où est-il allé ? » et « D'où vient-il ? ».
5. (2) L'arbre `role=tree` n'a pas de navigation clavier aux flèches. → Implémenter le motif d'arbre ARIA, ou une liste simple.
6. (2) Le statut du lot (bloqué, libéré) n'est pas visible dans la liste. → Colonne statut et filtre.
7. (2) L'état vide « Aucun lot. » n'a pas d'action. → « Réceptionner un lot ».
8. (3) Le choix du plan de contrôle se fait par une liste sans libellé visible. → Libellé visible.
9. (3) Pas de scan de code-barres sur tablette. → Champ « Scanner » (caméra).
10. (3) La date de péremption n'a pas d'alerte visuelle. → Alerte à J-7.

### Rappel (simulation et blocage)
1. (1) La confirmation du blocage est intégrée à la page et ne rappelle ni le nombre de lots ni les quantités. → Boîte de dialogue modale : « Bloquer 4 lots (1 240 u.) sur 2 sites ? », avec la saisie du numéro de lot pour les rappels critiques.
2. (1) En cas d'échec partiel, le message est générique (« vérifiez les lots restants »). → Liste des lots en échec, avec un bouton « Réessayer ceux-ci ».
3. (2) « Simuler un rappel » ouvre un panneau sans indiquer que rien n'est encore bloqué. → Bandeau « Simulation — aucun stock n'est bloqué ».
4. (2) Les tableaux « Aucun. » et « Aucun client livré. » ne se distinguent pas d'un chargement raté. → États vides rédigés.
5. (2) Pas d'export de la liste des clients à prévenir. → Bouton CSV/PDF.
6. (2) Le texte « Calcul du rappel… » s'affiche sans durée ni annulation. → Indicateur et bouton « Annuler ».
7. (2) « Fermer » est placé dans l'en-tête, au même niveau que l'action critique. → Bouton secondaire, ou Échap.
8. (3) Pas de journal visible du blocage (heure, auteur, webhook). → Ligne d'historique sur la fiche du lot.
9. (3) Pas de modèle d'e-mail client. → Studio, section Documents produits.
10. (3) Les quantités ne sont pas en chiffres tabulaires alignés à droite. → Colonnes en `num`.

### Paramétrage (référentiels, workflows, notifications, apparence)
1. (1) Apparence : aucune vérification du contraste (voir T3), et le logo est enregistré immédiatement, sans brouillon, alors que la couleur attend « Enregistrer ». → Tout passe par le brouillon puis la publication (Studio).
2. (1) Apparence : quitter l'écran annule l'aperçu sans prévenir. → Garde « modifications non publiées ».
3. (2) Workflows : les codes sont collés au libellé (« DECLAREEINITIAL ») et le diagramme est en texte. → Graphe lisible, avec les codes en infobulle.
4. (2) Notifications : les codes d'événement et `ESSOR_SMTP_*` s'affichent ; aucun e-mail de test n'est possible. → Libellés, bouton « Envoyer un test », modèles (Studio).
5. (2) Référentiels : le format d'import est expliqué dans un libellé de 80 caractères. → Assistant d'import en 3 étapes, avec un rapport d'erreurs par ligne.
6. (2) Référentiels : 59 éléments, dont 35 cibles de moins de 40 px, sur tablette. → Lignes de 44 px.
7. (2) L'administration est éparpillée dans 6 menus, sans page d'accueil. → Page « Paramétrage » qui regroupe les sections et ouvre le Studio.
8. (2) Pas de journal des modifications de configuration. → Historique des versions (Studio, section Gouvernance).
9. (3) « Enregistrer » est en bas des notifications, hors de vue. → Barre d'enregistrement collante.
10. (3) Aucun aperçu PDF ou e-mail réel n'est généré. → Aperçu en direct (Studio).

### Les 5 problèmes les plus graves
1. Couleur libre sans contrôle de contraste : un jaune rend les boutons et le focus illisibles (T3).
2. Redirection silencieuse des routes interdites, et menus menant à des impasses (T1, T2).
3. Fiche : bouton de transition désactivé et muet, double enregistrement, perte de saisie possible (fiche NC 1 à 3).
4. Tablette et mobile : tableaux écrasés, en-tête envahissant, cibles de moins de 44 px (T4 à T6).
5. Rappel : confirmation du blocage sans récapitulatif chiffré, et échec partiel opaque (Rappel 1 et 2).

## 2. Studio de personnalisation (rôle ADMIN)

Principe : **socle verrouillé + couches surchargées**. La charte ESSOR définit des jetons et des composants. Le client ne change que des valeurs, chacune bornée par un garde-fou. Il ne touche jamais au CSS libre ni au HTML.

Verrouillé dans tous les cas : structure des écrans, couleurs d'état (critique, alerte, succès, info), anneau de focus, texte d'au moins 14 px, cibles de 44 px sur écran tactile, thème Terrain, champs exigés par le workflow, contrôle d'accès côté serveur.

### 2.1 Identité
| Réglage | Garde-fou |
|---|---|
| Logo clair et logo foncé (SVG/PNG ≤ 512 Ko), favicon (PNG 32/180) | Type MIME vérifié côté serveur, SVG nettoyé (pas de script ni de lien externe), dimensions minimales et maximales, recadrage dans un carré pour le rail. |
| Nom affiché | 1 à 40 caractères, sans balise ; « ESSOR QMS » reste en mention secondaire. |
| Couleur principale | Contraste ≥ 3:1 avec le fond clair et le fond sombre, et ≥ 4,5:1 avec le texte du bouton. Le texte du bouton est calculé (blanc ou noir). Si le seuil n'est pas atteint, la publication est bloquée et une **variante accessible** (couleur assombrie ou éclaircie) est proposée. Une variante sombre est dérivée automatiquement. |
| Couleur secondaire | Mêmes règles ; elle ne peut pas être trop proche des couleurs d'état (écart ΔE ≥ 20 avec le rouge critique), pour éviter la confusion. |
| Police | Liste fermée embarquée dans `assets/fonts` : Archivo (charte), IBM Plex Sans, Source Sans 3, Atkinson Hyperlegible, plus Noto Sans Arabic pour l'arabe. Les chiffres tabulaires restent en IBM Plex Mono. |
| Arrondis | 0 à 8 px. |
| Densité | Compacte, standard ou confortable. La densité compacte est ignorée sous 1024 px et sur écran tactile. |
| Thème | Thème par défaut du tenant ; option « l'utilisateur peut choisir » ; Terrain toujours disponible. |

### 2.2 Vocabulaire
- Dictionnaire de clés (`term.nc`, `term.action`, `term.incident`…) avec, pour chaque clé, le singulier, le pluriel, le genre (m/f) et l'élision (l'/le). Les phrases du produit utilisent ICU MessageFormat (`{term.nc, select, f {une} m {un}} {term.nc}`) pour rester correctes après un renommage.
- Locales : `fr-MA` d'abord, puis `ar-MA` (avec RTL, prévu dans les jetons logiques `margin-inline`) et `en`.
- Garde-fous : 1 à 30 caractères ; unicité (deux termes ne peuvent pas devenir identiques) ; liste de termes réservés ; aperçu des 10 phrases les plus fréquentes avant publication.
- Portée : web, mobile, PDF, e-mails, exports.

### 2.3 Navigation
- Par rôle : ordre, visibilité, regroupements (renommer « Terrain » et les autres groupes), page d'accueil.
- Garde-fous : « Mes actions » et « Paramétrage » (ADMIN) ne peuvent pas être masqués. Masquer un menu **ne retire aucun droit** : l'API reste l'autorité. Un module non livré est masqué par défaut.

### 2.4 Formulaires par type de fiche
- Pour chaque type de fiche et chaque étape : champs visibles, obligatoires, ordre, sections, libellés, aide contextuelle.
- Champs personnalisés : texte (≤ 500), liste (valeurs gérées, ≤ 100), date, nombre (min, max, unité), case à cocher. Clé technique stable (`cf_quai`) générée une fois et jamais réutilisée.
- Garde-fous : les champs exigés par le workflow (`requires` des transitions) ne peuvent pas être masqués ni rendus facultatifs ; 20 champs personnalisés au plus par type ; un champ supprimé est archivé, et ses données sont conservées.
- Aperçu en direct, au format poste et au format mobile.

### 2.5 Tableau de bord
- Catalogue de widgets (KPI, liste, histogramme, carte de contrôle), placés sur une grille de 12 colonnes, avec des tailles S/M/L par rôle.
- Indicateur simple = un type de fiche + des filtres + une agrégation (compte, somme d'un champ numérique) + une période + un seuil d'alerte. Pas de requête libre.
- Garde-fous : 12 widgets au plus ; une requête est bornée (index existants, 13 mois au plus).

### 2.6 Listes
- Colonnes visibles et ordre par défaut (choix du tenant) ; surcharge par utilisateur.
- Filtres enregistrés : vues personnelles ou partagées avec un rôle ou un site.
- Garde-fous : la colonne de référence reste fixe ; 10 colonnes au plus.

### 2.7 Documents produits
- PDF : en-tête (logo, nom, adresse, mention), pied de page (confidentialité, pagination imposée), choix du logo clair.
- E-mails : modèles par événement (sujet, corps en Markdown restreint), avec des variables sur liste blanche (`{{fiche.reference}}`, `{{fiche.titre}}`, `{{destinataire.prenom}}`, `{{lien}}`). Une variable inconnue est refusée à la validation. Envoi de test.
- Garde-fous : pas de HTML libre, échappement systématique, lien toujours relatif à l'instance.

### 2.8 Gouvernance
- **Brouillon unique** par tenant, éditable par les ADMIN, avec un verrou optimiste (ETag).
- **Aperçu** : le brouillon est appliqué à la session de l'administrateur seulement (`?preview=draft`, bandeau « Aperçu non publié »).
- **Publication** : validation serveur complète, puis nouvelle version immuable `vN` ; elle est diffusée aux clients au prochain chargement ou par SSE.
- **Historique** : auteur, date, résumé du diff ; **restauration** d'une version en brouillon.
- **Export et import JSON**, signé par une empreinte SHA-256 ; l'import crée un brouillon et affiche le diff. Les IDs de référentiels (sites, listes) sont résolus par code, pour passer d'un site de test à la production.
- **Revenir à la charte ESSOR** : crée un brouillon égal aux valeurs par défaut.
- Journal d'audit de chaque publication (exigence ISO 9001 §7.5).

## 3. Contrat d'API

Tous les appels sont limités au tenant de la session (RLS). Écriture : rôle ADMIN plus CSRF. Lecture de la version publiée : tout utilisateur authentifié.

```
GET    /api/customization/published              → 200 {version, etag, config}   (Cache-Control: no-cache, ETag)
GET    /api/customization/draft                  → 200 {baseVersion, etag, config} | 404
PUT    /api/customization/draft   If-Match       → 200 {etag, warnings[]} | 409 conflit | 422 {errors[]}
POST   /api/customization/draft/validate         → 200 {errors[], warnings[], contrast:[...]}
POST   /api/customization/draft/publish If-Match → 201 {version}
DELETE /api/customization/draft                  → 204
GET    /api/customization/versions?page=         → 200 {items:[{version, author, publishedAt, summary}], total}
GET    /api/customization/versions/{v}           → 200 {config}
POST   /api/customization/versions/{v}/restore   → 200 brouillon
GET    /api/customization/export?version=        → application/json (+ sha256)
POST   /api/customization/import                 → 200 brouillon + diff | 422
POST   /api/customization/assets (multipart: logoLight|logoDark|favicon) → 201 {assetId, url}
GET    /api/customization/assets/{assetId}       → binaire (Cache-Control immutable)
```
Format d'erreur : `{ "code": "CONTRAST_TOO_LOW", "path": "/identity/primary", "message": "…", "suggestion": "#886e00" }`.

Schéma (JSON Schema 2020-12, `schemaVersion` entier, migrations côté serveur) :
```json
{
  "schemaVersion": 1,
  "identity": { "appName": "Atlas Logistique & Industrie", "logoLight": "asset:…", "logoDark": "asset:…", "favicon": "asset:…",
                "primary": "#c2410c", "secondary": "#1f5fa8", "font": "archivo", "radius": 0,
                "density": "standard", "defaultTheme": "clair", "userThemeChoice": true },
  "vocabulary": { "fr-MA": { "term.nc": { "singular": "Écart", "plural": "Écarts", "gender": "m" } } },
  "navigation": { "QUALITY_MANAGER": { "home": "/mes-actions",
                  "groups": [ { "label": "Terrain", "items": [ { "id": "nc", "visible": true } ] } ] } },
  "forms": { "NC": { "steps": { "DECLAREE": { "sections": [ { "label": "Constat",
             "fields": [ { "key": "title", "required": true }, { "key": "cf_quai", "label": "N° de quai",
             "type": "text", "required": false, "help": "Numéro peint au sol" } ] } ] } } } },
  "dashboards": { "QUALITY_MANAGER": [ { "widget": "kpi", "size": "S", "indicator": "ind_nc_ouvertes" } ] },
  "indicators": [ { "id": "ind_nc_ouvertes", "recordType": "NC", "filter": { "stage": ["!CLOTUREE"] }, "agg": "count", "period": "P30D", "alertAbove": 15 } ],
  "lists": { "NC": { "columns": ["ref", "title", "site", "severity", "stage"], "sharedViews": [] } },
  "documents": { "pdf": { "header": "…", "footer": "…" }, "mail": { "RECORD_ASSIGNED": { "subject": "…", "body": "…" } } }
}
```
Validation serveur (bloquante, identique au front, avec des vecteurs de test partagés) : schéma ; contraste WCAG (fond clair, fond sombre, texte calculé) ; bornes ; police dans la liste ; clés de vocabulaire connues et unicité ; navigation (éléments non masquables) ; formulaires (champs `requires` des workflows) ; variables de modèle sur liste blanche ; tailles. Stockage : table `customization_version(tenant_id, version, config jsonb, author, published_at, sha256)` et `customization_draft(tenant_id, config jsonb, etag)`, avec RLS.

### Application côté front au démarrage
1. `APP_INITIALIZER` : `GET /published`, avec une copie en cache local (dernière version connue) pour démarrer hors ligne. Les valeurs de la charte servent de repli si l'appel échoue.
2. **Jetons** : `--acc`, `--acc-ink` (calculé), `--acc2`, `--font`, `--r`, densité (`--pad`, `--row`, `--fs`), posés sur `:root`. `BrandingService` est étendu ; aucun style en ligne dans les composants. Favicon et `document.title` sont mis à jour.
3. **Libellés** : un `LabelService` (signal) fournit un pipe `term` et des messages ICU ; tous les textes « métier » passent par une clé (refonte progressive : `navigation.model.ts`, `record-type-view.ts`, titres).
4. **Navigation** : `navigation.model.ts` est filtré et ordonné par la configuration du rôle. Les gardes de route restent fondées sur les permissions.
5. **Formulaires** : un `FormSchemaService` fournit, par type et étape, la liste ordonnée des champs. Un rendu générique `eq-dynamic-field` affiche les champs personnalisés, stockés dans `record.custom jsonb` et validés par le serveur.
6. Aperçu : en mode `preview=draft`, la configuration du brouillon remplace la version publiée pour la session ADMIN seulement.

## 4. Découpage en tâches
**Back**
- B1 Schéma JSON, migrations, tables et RLS.
- B2 Endpoints brouillon, publication, versions, export et import.
- B3 Validateur (contraste, bornes, cohérence avec le workflow), vecteurs de test partagés.
- B4 Stockage des fichiers (logo, favicon) et nettoyage des SVG.
- B5 Champs personnalisés : `custom jsonb`, validation selon le schéma publié, export.
- B6 PDF et e-mails : modèles à variables en liste blanche, envoi de test.
- B7 Indicateurs simples (requêtes bornées) et widgets.
- B8 Vues de liste enregistrées et partagées.
- B9 Journal d'audit des publications.

**Web**
- W1 Corrections d'audit transverses (T1 à T9).
- W2 Application de la configuration au démarrage (jetons, favicon, titre).
- W3 `LabelService` et migration des libellés.
- W4 Navigation par rôle.
- W5 Studio : coque, brouillon, aperçu, publication, historique, import et export.
- W6 Studio, sections Identité (avec garde-fous) et Vocabulaire.
- W7 Studio, éditeur de formulaires et `eq-dynamic-field`.
- W8 Studio, tableau de bord et listes.
- W9 Studio, documents produits.
- W10 Tests e2e et axe-core sur 3 configurations extrêmes (jaune, densité compacte, termes longs).

**Mobile (Flutter)**
- M1 Récupération et cache de la configuration publiée, application de l'identité (`ThemeData` dérivé, logo, nom).
- M2 Vocabulaire (ARB enrichi des surcharges du tenant).
- M3 Formulaires dynamiques pour la déclaration NC et incident hors ligne (schéma embarqué dans le cache, version notée sur chaque brouillon).
- M4 Gestion d'un changement de schéma pendant qu'un brouillon hors ligne existe.

## 5. Risques
| Risque | Parade |
|---|---|
| Libellés codés en dur : renommage incomplet | Inventaire et test qui refuse toute chaîne métier hors clé. |
| Grammaire cassée après renommage (genre, élision) | ICU MessageFormat, genre obligatoire, aperçu des phrases. |
| Brouillon mobile hors ligne fondé sur un ancien schéma de formulaire | Version du schéma stockée avec le brouillon ; le serveur accepte N-1 et ignore les champs retirés. |
| Configuration invalide importée en production | Import en brouillon seulement, diff, validation serveur bloquante. |
| Dérive de la charte et support plus coûteux | Jetons bornés, verrous, bouton « charte ESSOR », indication de la version en support. |
| Performance des indicateurs personnalisés | Requêtes bornées sur des index, cache de 5 minutes. |
| Sécurité : SVG malveillant, injection dans les modèles | Nettoyage, CSP, variables sur liste blanche, échappement. |
| Accessibilité dégradée par une police ou une densité | Liste fermée, densité compacte interdite sur écran tactile, axe-core en CI. |
| Arabe (RTL) prévu tardivement | Propriétés CSS logiques dès W2. |
