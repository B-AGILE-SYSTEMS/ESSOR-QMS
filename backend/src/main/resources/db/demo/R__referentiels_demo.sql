-- Données de démonstration M3 (profil "demo") : référentiels ISO 9001:2015 et ISO 45001:2018 (titres des chapitres
-- et sous-chapitres, résumés d'une phrase rédigés par nous, sans reprise du texte des normes), un audit planifié
-- et une procédure. Idempotent.

DO $$
DECLARE
    t       uuid := '00000000-0000-0000-0000-00000000d001';
    s9001   uuid := '00000000-0000-0000-0000-000000009001';
    s45001  uuid := '00000000-0000-0000-0000-000000045001';
    depot   uuid := '00000000-0000-0000-0000-0000000051a2';
    nord    uuid;
    u_qualite uuid;
    u_terrain uuid;
    y       int := extract(year FROM current_date);
    n_aud   int;
    n_doc   int;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenant WHERE id = t) THEN
        RETURN;
    END IF;
    INSERT INTO standard (id, tenant_id, code, version, title) VALUES
        (s9001, t, 'ISO9001', '2015', 'ISO 9001 — Systèmes de management de la qualité'),
        (s45001, t, 'ISO45001', '2018', 'ISO 45001 — Systèmes de management de la santé et sécurité au travail')
    ON CONFLICT DO NOTHING;

    CREATE TEMP TABLE demo_req (std uuid, code text, title text, text text, parent text, auditable boolean,
                                pos serial) ON COMMIT DROP;
    INSERT INTO demo_req (std, code, title, text, parent, auditable) VALUES
     (s9001, '4', 'Contexte de l''organisme', 'Situer l''entreprise dans son environnement avant de bâtir le système.', NULL, false),
     (s9001, '4.1', 'Compréhension de l''organisme et de son contexte', 'Identifier les enjeux internes et externes qui influencent la qualité.', '4', true),
     (s9001, '4.2', 'Besoins et attentes des parties intéressées', 'Savoir qui attend quoi de l''entreprise et suivre ces attentes.', '4', true),
     (s9001, '4.3', 'Domaine d''application du système', 'Fixer clairement les sites, activités et produits couverts.', '4', true),
     (s9001, '4.4', 'Système de management de la qualité et processus', 'Décrire les processus, leurs liens et leurs indicateurs.', '4', true),
     (s9001, '5', 'Leadership', 'La direction porte et anime le système.', NULL, false),
     (s9001, '5.1', 'Leadership et engagement', 'La direction s''implique visiblement et place le client au centre.', '5', true),
     (s9001, '5.2', 'Politique qualité', 'Une politique écrite, diffusée et comprise oriente les décisions.', '5', true),
     (s9001, '5.3', 'Rôles, responsabilités et autorités', 'Chacun sait ce dont il est responsable dans le système.', '5', true),
     (s9001, '6', 'Planification', 'Anticiper plutôt que subir.', NULL, false),
     (s9001, '6.1', 'Actions face aux risques et opportunités', 'Traiter les risques et saisir les opportunités de façon planifiée.', '6', true),
     (s9001, '6.2', 'Objectifs qualité et planification', 'Des objectifs mesurables, suivis, avec des moyens associés.', '6', true),
     (s9001, '6.3', 'Planification des modifications', 'Changer le système de façon maîtrisée.', '6', true),
     (s9001, '7', 'Support', 'Les moyens nécessaires au fonctionnement du système.', NULL, false),
     (s9001, '7.1', 'Ressources', 'Personnel, infrastructures, environnement de travail et équipements de mesure adaptés.', '7', true),
     (s9001, '7.2', 'Compétences', 'Les compétences requises sont définies, acquises et prouvées.', '7', true),
     (s9001, '7.3', 'Sensibilisation', 'Le personnel connaît la politique et l''effet de son travail.', '7', true),
     (s9001, '7.4', 'Communication', 'Savoir quoi communiquer, à qui, quand et comment.', '7', true),
     (s9001, '7.5', 'Informations documentées', 'Documents et enregistrements créés, maîtrisés et conservés.', '7', true),
     (s9001, '8', 'Réalisation des activités opérationnelles', 'Produire et livrer ce qui a été promis.', NULL, false),
     (s9001, '8.1', 'Planification et maîtrise opérationnelles', 'Organiser la production avec des critères clairs.', '8', true),
     (s9001, '8.2', 'Exigences relatives aux produits et services', 'Comprendre et revoir la demande du client avant de s''engager.', '8', true),
     (s9001, '8.3', 'Conception et développement', 'Concevoir par étapes revues, vérifiées et validées.', '8', true),
     (s9001, '8.4', 'Maîtrise des prestataires externes', 'Choisir, évaluer et surveiller fournisseurs et sous-traitants.', '8', true),
     (s9001, '8.5', 'Production et prestation de service', 'Produire dans des conditions maîtrisées et tracées.', '8', true),
     (s9001, '8.6', 'Libération des produits et services', 'Ne livrer qu''après vérification des critères d''acceptation.', '8', true),
     (s9001, '8.7', 'Maîtrise des éléments de sortie non conformes', 'Identifier et isoler ce qui n''est pas conforme pour éviter sa livraison.', '8', true),
     (s9001, '9', 'Évaluation des performances', 'Mesurer pour piloter.', NULL, false),
     (s9001, '9.1', 'Surveillance, mesure, analyse et évaluation', 'Suivre les indicateurs et la satisfaction client.', '9', true),
     (s9001, '9.2', 'Audit interne', 'Vérifier régulièrement que le système fonctionne comme prévu.', '9', true),
     (s9001, '9.3', 'Revue de direction', 'La direction fait le point à intervalles planifiés et décide.', '9', true),
     (s9001, '10', 'Amélioration', 'Progresser en continu.', NULL, false),
     (s9001, '10.1', 'Généralités', 'Rechercher les occasions d''améliorer produits, services et système.', '10', true),
     (s9001, '10.2', 'Non-conformité et action corrective', 'Traiter les écarts et supprimer leurs causes.', '10', true),
     (s9001, '10.3', 'Amélioration continue', 'Rendre le système chaque année plus pertinent et plus efficace.', '10', true),

     (s45001, '4', 'Contexte de l''organisme', 'Comprendre l''environnement santé et sécurité de l''entreprise.', NULL, false),
     (s45001, '4.1', 'Compréhension de l''organisme et de son contexte', 'Identifier les enjeux qui pèsent sur la santé et la sécurité.', '4', true),
     (s45001, '4.2', 'Besoins et attentes des travailleurs et des parties intéressées', 'Recenser ce qu''attendent les salariés, l''administration et les autres acteurs.', '4', true),
     (s45001, '4.3', 'Domaine d''application du système S&ST', 'Délimiter les sites et activités couverts.', '4', true),
     (s45001, '4.4', 'Système de management de la S&ST', 'Mettre en place et faire vivre le système et ses processus.', '4', true),
     (s45001, '5', 'Leadership et participation des travailleurs', 'La direction s''engage et associe les salariés.', NULL, false),
     (s45001, '5.1', 'Leadership et engagement', 'La direction assume la prévention des lésions et des atteintes à la santé.', '5', true),
     (s45001, '5.2', 'Politique de S&ST', 'Une politique écrite engage à des conditions de travail sûres et saines.', '5', true),
     (s45001, '5.3', 'Rôles, responsabilités et autorités', 'Les responsabilités santé et sécurité sont attribuées et connues.', '5', true),
     (s45001, '5.4', 'Consultation et participation des travailleurs', 'Les salariés sont consultés et associés aux décisions qui les concernent.', '5', true),
     (s45001, '6', 'Planification', 'Identifier les dangers et planifier la prévention.', NULL, false),
     (s45001, '6.1', 'Actions face aux risques et opportunités', 'Ensemble des démarches d''anticipation des risques.', '6', false),
     (s45001, '6.1.1', 'Généralités', 'Disposer d''une démarche de planification adaptée aux activités.', '6.1', true),
     (s45001, '6.1.2', 'Identification des dangers et évaluation des risques', 'Recenser les dangers de chaque poste et évaluer les risques en continu.', '6.1', true),
     (s45001, '6.1.3', 'Exigences légales et autres exigences', 'Connaître et tenir à jour les obligations réglementaires applicables.', '6.1', true),
     (s45001, '6.1.4', 'Planification d''actions', 'Planifier les actions de prévention et mesurer leur efficacité.', '6.1', true),
     (s45001, '6.2', 'Objectifs de S&ST et planification', 'Des objectifs de prévention mesurables et suivis.', '6', true),
     (s45001, '7', 'Support', 'Les moyens au service de la prévention.', NULL, false),
     (s45001, '7.1', 'Ressources', 'Prévoir les moyens humains, matériels et financiers.', '7', true),
     (s45001, '7.2', 'Compétences', 'Former et habiliter chaque salarié selon les risques de son poste.', '7', true),
     (s45001, '7.3', 'Sensibilisation', 'Chacun connaît les dangers de son poste et peut se retirer d''une situation dangereuse.', '7', true),
     (s45001, '7.4', 'Communication', 'Organiser la communication interne et externe sur la santé et la sécurité.', '7', true),
     (s45001, '7.5', 'Informations documentées', 'Maîtriser les documents et conserver les preuves.', '7', true),
     (s45001, '8', 'Réalisation des activités opérationnelles', 'Maîtriser les risques au quotidien.', NULL, false),
     (s45001, '8.1', 'Planification et maîtrise opérationnelles', 'Ensemble des mesures de maîtrise des opérations.', '8', false),
     (s45001, '8.1.1', 'Généralités', 'Définir des critères de travail sûrs et les appliquer.', '8.1', true),
     (s45001, '8.1.2', 'Élimination des dangers et réduction des risques', 'Supprimer le danger en priorité, puis protéger collectivement, puis individuellement.', '8.1', true),
     (s45001, '8.1.3', 'Management du changement', 'Évaluer les risques avant tout changement d''organisation, de matériel ou de procédé.', '8.1', true),
     (s45001, '8.1.4', 'Achats', 'Intégrer la sécurité dans les achats, la sous-traitance et l''intervention d''entreprises extérieures.', '8.1', true),
     (s45001, '8.2', 'Préparation et réponse aux situations d''urgence', 'Préparer, exercer et améliorer les plans d''urgence.', '8', true),
     (s45001, '9', 'Évaluation des performances', 'Mesurer l''efficacité de la prévention.', NULL, false),
     (s45001, '9.1', 'Surveillance, mesure, analyse et évaluation', 'Suivre les indicateurs santé et sécurité et la conformité réglementaire.', '9', true),
     (s45001, '9.2', 'Audit interne', 'Auditer le système à intervalles planifiés.', '9', true),
     (s45001, '9.3', 'Revue de direction', 'La direction examine les résultats et décide des suites.', '9', true),
     (s45001, '10', 'Amélioration', 'Tirer les leçons des événements.', NULL, false),
     (s45001, '10.1', 'Généralités', 'Rechercher les occasions d''améliorer la prévention.', '10', true),
     (s45001, '10.2', 'Événements indésirables, non-conformités et actions correctives', 'Analyser accidents, presqu''accidents et écarts, puis agir sur les causes.', '10', true),
     (s45001, '10.3', 'Amélioration continue', 'Faire progresser la performance santé et sécurité.', '10', true);

    INSERT INTO requirement (tenant_id, standard_id, code, title, text, position, auditable)
    SELECT t, d.std, d.code, d.title, d.text, d.pos, d.auditable FROM demo_req d
    ON CONFLICT (tenant_id, standard_id, code) DO UPDATE
        SET title = excluded.title, text = excluded.text, position = excluded.position, auditable = excluded.auditable;
    UPDATE requirement r SET parent_id = p.id
    FROM demo_req d JOIN requirement p ON p.tenant_id = t AND p.standard_id = d.std AND p.code = d.parent
    WHERE r.tenant_id = t AND r.standard_id = d.std AND r.code = d.code;

    -- Site de l'audit de démo : Entrepôt Nord (créé si absent), rattaché à tous les utilisateurs du tenant.
    INSERT INTO site (tenant_id, code, name)
    SELECT t, 'NORD', 'Entrepôt Nord'
    WHERE NOT EXISTS (SELECT 1 FROM site WHERE tenant_id = t AND code = 'NORD');
    SELECT id INTO nord FROM site WHERE tenant_id = t AND code = 'NORD';
    INSERT INTO user_site (tenant_id, user_id, site_id)
    SELECT t, u.id, nord FROM app_user u WHERE u.tenant_id = t
    ON CONFLICT DO NOTHING;
    -- Auditeur désigné : l'utilisateur "terrain" (compte préparé "pending:terrain", même id après sa connexion).
    SELECT id INTO u_qualite FROM app_user WHERE tenant_id = t AND username = 'qualite' LIMIT 1;
    SELECT id INTO u_terrain FROM app_user WHERE tenant_id = t AND username = 'terrain' LIMIT 1;

    -- Audit planifié et procédure (une seule fois ; l'audit existant est réaligné sur le site et l'auditeur)
    IF EXISTS (SELECT 1 FROM record WHERE id = '00000000-0000-0000-0000-0000000a0d01') THEN
        UPDATE record SET site_id = nord, title = 'Audit interne ISO 45001 — Entrepôt Nord',
               data = data || jsonb_build_object('auditeurs',
                   CASE WHEN u_terrain IS NULL THEN '[]'::jsonb ELSE jsonb_build_array(u_terrain::text) END)
        WHERE id = '00000000-0000-0000-0000-0000000a0d01' AND status = 'PLANIFIE'
          AND (site_id IS DISTINCT FROM nord OR NOT (data->'auditeurs' ? coalesce(u_terrain::text, '')));
        RETURN;
    END IF;
    SELECT coalesce(max(last_value), 0) INTO n_aud FROM record_sequence WHERE tenant_id = t AND prefix = 'AUD' AND year = y;
    SELECT coalesce(max(last_value), 0) INTO n_doc FROM record_sequence WHERE tenant_id = t AND prefix = 'DOC' AND year = y;
    INSERT INTO record (id, tenant_id, type_code, reference, status, title, site_id, owner_id, data, created_by) VALUES
     ('00000000-0000-0000-0000-0000000a0d01', t, 'AUDIT', 'AUD-' || y || '-' || lpad((n_aud + 1)::text, 4, '0'),
      'PLANIFIE', 'Audit interne ISO 45001 — Entrepôt Nord', nord, u_qualite,
      jsonb_build_object('standardIds', jsonb_build_array(s45001::text), 'perimetre', 'Réception, stockage, expédition',
                         'auditeurs', CASE WHEN u_terrain IS NULL THEN '[]'::jsonb ELSE jsonb_build_array(u_terrain::text) END, 'audites', 'Chef d''entrepôt, caristes',
                         'datePrevue', to_char(current_date + 14, 'YYYY-MM-DD')), u_qualite),
     ('00000000-0000-0000-0000-0000000d0c01', t, 'DOCUMENT', 'DOC-' || y || '-' || lpad((n_doc + 1)::text, 4, '0'),
      'REDACTION', 'Circulation des chariots élévateurs', depot, u_qualite,
      jsonb_build_object('code', 'PR-QHSE-07', 'docType', 'procedure', 'processus', 'Logistique',
                         'recipients', jsonb_build_object('roles', jsonb_build_array('FIELD_USER'), 'siteIds', jsonb_build_array()),
                         'verifierRoles', jsonb_build_array('QUALITY_MANAGER'), 'approverRoles', jsonb_build_array('ADMIN'),
                         'reviewDate', to_char(current_date + 365, 'YYYY-MM-DD')), u_qualite);
    INSERT INTO record_sequence (tenant_id, prefix, year, last_value) VALUES (t, 'AUD', y, n_aud + 1), (t, 'DOC', y, n_doc + 1)
    ON CONFLICT (tenant_id, prefix, year)
        DO UPDATE SET last_value = greatest(record_sequence.last_value, excluded.last_value);
END
$$;
