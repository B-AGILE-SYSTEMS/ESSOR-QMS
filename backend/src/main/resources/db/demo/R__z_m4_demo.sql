-- Données de démonstration M4 (profil "demo"), après les données M3b (ordre alphabétique des scripts répétables).
-- Idempotent : rien n'est fait si l'instrument DES-02 existe déjà.
-- (1) DES-02 trouvé hors tolérance aujourd'hui → bloqué, NC ; il a mesuré l'humidité du lot LF-2026-0311 :
--     GET /api/instruments/00000000-0000-0000-0000-00000000e101/impact puis POST .../impact/quarantine.
-- (2) USINE-CASA : risque critique « brûlure four tunnel » dont l'action de maîtrise est une fiche ACTION.
-- (3) Loi 65-99 art. 281 évaluée non conforme à l'usine → action de mise en conformité.
-- (4) FOU-MOULINS : lot refusé, NC liée et livraison en retard ce trimestre ; campagne trimestrielle à faire.

DO $$
DECLARE
    t       uuid := '00000000-0000-0000-0000-00000000d001';
    usine   uuid := '00000000-0000-0000-0000-0000000051a1';
    depot   uuid := '00000000-0000-0000-0000-0000000051a2';
    u_q     uuid;
    y       int := extract(year FROM current_date);
    n_nc    int;
    n_act   int;
    i_des   uuid := '00000000-0000-0000-0000-00000000e101';
    i_pac   uuid := '00000000-0000-0000-0000-00000000e102';
    i_bal   uuid := '00000000-0000-0000-0000-00000000e103';
    i_the   uuid := '00000000-0000-0000-0000-00000000e104';
    nc_des  uuid := '00000000-0000-0000-0000-00000000e201';
    nc_fou  uuid := '00000000-0000-0000-0000-00000000e202';
    a_risk  uuid := '00000000-0000-0000-0000-00000000e301';
    a_reg   uuid := '00000000-0000-0000-0000-00000000e302';
    a_prog  uuid := '00000000-0000-0000-0000-00000000e303';
    sc      uuid := '00000000-0000-0000-0000-00000000e401';
    r_four  uuid := '00000000-0000-0000-0000-00000000e501';
    g_fou   uuid := '00000000-0000-0000-0000-00000000e601';
    g_cli   uuid := '00000000-0000-0000-0000-00000000e602';
    camp    uuid := '00000000-0000-0000-0000-00000000e701';
    f_mou   uuid := '00000000-0000-0000-0000-00000000b201';
    f_suc   uuid := '00000000-0000-0000-0000-00000000b202';
    a_far   uuid := '00000000-0000-0000-0000-00000000b101';
    i_rec   uuid := '00000000-0000-0000-0000-00000000b501';
    l_new   uuid := '00000000-0000-0000-0000-00000000e801';
    reg     uuid;
    req     uuid;
    q_start date := date_trunc('quarter', current_date)::date;
    q_code  text := extract(year FROM current_date) || '-T' || extract(quarter FROM current_date);
    p_start date := (date_trunc('quarter', current_date) - interval '3 months')::date;
    p_code  text;
    rec     record;
    k       int := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM site WHERE id = usine) OR EXISTS (SELECT 1 FROM instrument WHERE id = i_des) THEN
        RETURN;
    END IF;
    SELECT id INTO u_q FROM app_user WHERE tenant_id = t AND username = 'qualite';
    p_code := extract(year FROM p_start) || '-T' || extract(quarter FROM p_start);
    SELECT coalesce(max(last_value), 0) INTO n_nc FROM record_sequence WHERE tenant_id = t AND prefix = 'NC' AND year = y;
    SELECT coalesce(max(last_value), 0) INTO n_act FROM record_sequence WHERE tenant_id = t AND prefix = 'ACT' AND year = y;

    -- ------------------------------------------------------------------ métrologie
    INSERT INTO instrument (id, tenant_id, code, label, type, site_id, location, serial_number, range_min, range_max,
                            unit, resolution, emt, period_months, provider, status, next_due) VALUES
        (i_des, t, 'DES-02', 'Dessiccateur halogène', 'dessiccateur', usine, 'Laboratoire réception', 'MB-77-0412',
         0, 100, '%', 0.01, 0.2, 12, 'Labo Métrologie Casablanca', 'bloque', NULL),
        (i_pac, t, 'PAC-031', 'Pied à coulisse 150 mm', 'pied_a_coulisse', usine, 'Atelier conditionnement',
         'PC-150-031', 0, 150, 'mm', 0.01, 0.03, 12, 'Interne', 'en_service', current_date + 200),
        (i_bal, t, 'BAL-07', 'Balance de contrôle 6 kg', 'balance', usine, 'Ligne biscuits', 'BX-6000-07', 0, 6000,
         'g', 0.1, 0.5, 6, 'Labo Métrologie Casablanca', 'en_service', current_date + 10),
        (i_the, t, 'THE-12', 'Thermomètre à sonde', 'thermometre', depot, 'Quai froid', 'TS-12', -40, 120, '°C',
         0.1, 0.5, 12, 'Interne', 'en_service', current_date - 5);

    INSERT INTO record (id, tenant_id, type_code, reference, status, title, description, severity, site_id, data,
                        created_at, created_by) VALUES
        (nc_des, t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 1)::text, 4, '0'), 'DECLAREE',
         'Instrument DES-02 hors tolérance à l''étalonnage',
         'Écart maximal 0.35 pour une EMT de 0.2 %. Instrument bloqué ; évaluer l''effet sur les contrôles et lots.',
         'majeure', usine, jsonb_build_object('origine', 'etalonnage', 'instrumentId', i_des, 'instrumentCode', 'DES-02',
         'ecartMax', 0.35, 'emt', 0.2), now(), u_q),
        (nc_fou, t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 2)::text, 4, '0'), 'QUALIFIEE',
         'Farine humide à réception (Moulins du Souss)', 'Humidité 16,2 % sur le lot LF-DEMO-M4.', 'majeure', usine,
         jsonb_build_object('origine', 'controle', 'supplierId', f_mou, 'supplierCode', 'FOU-MOULINS'), now(), u_q);

    INSERT INTO calibration (tenant_id, instrument_id, calibrated_on, provider, certificate_number, points, emt,
                             max_abs_error, verdict, next_due, nc_id, created_by, created_at) VALUES
        (t, i_des, current_date - 300, 'Labo Métrologie Casablanca', 'LMC-2025-1187',
         '[{"nominal":5.0,"mesure":5.05,"ecart":0.05,"conforme":true},{"nominal":15.0,"mesure":14.92,"ecart":-0.08,"conforme":true}]',
         0.2, 0.08, 'conforme', current_date + 65, NULL, u_q, now() - interval '300 days'),
        (t, i_des, current_date, 'Labo Métrologie Casablanca', 'LMC-' || y || '-0871',
         '[{"nominal":5.0,"mesure":5.12,"ecart":0.12,"conforme":true},{"nominal":15.0,"mesure":15.35,"ecart":0.35,"conforme":false}]',
         0.2, 0.35, 'non_conforme', NULL, nc_des, u_q, now()),
        (t, i_pac, current_date - 165, 'Interne', 'INT-PAC-031',
         '[{"nominal":10,"mesure":10.01,"ecart":0.01,"conforme":true},{"nominal":100,"mesure":99.98,"ecart":-0.02,"conforme":true}]',
         0.03, 0.02, 'conforme', current_date + 200, NULL, u_q, now() - interval '165 days');
    UPDATE inspection_result SET instrument_id = i_des WHERE inspection_id = i_rec AND characteristic = 'HUM';

    -- ------------------------------------------------------------------ risques
    INSERT INTO risk_scale (id, tenant_id, code, label, dimensions, thresholds) VALUES
        (sc, t, 'DEFAUT', 'Gravité × probabilité (4 × 4)',
         '[{"code":"gravite","label":"Gravité","inFormula":true,"levels":[{"value":1,"label":"Bénigne"},{"value":2,"label":"Sérieuse"},{"value":3,"label":"Grave"},{"value":4,"label":"Très grave"}]},
           {"code":"probabilite","label":"Probabilité","inFormula":true,"levels":[{"value":1,"label":"Rare"},{"value":2,"label":"Possible"},{"value":3,"label":"Probable"},{"value":4,"label":"Fréquente"}]},
           {"code":"maitrise","label":"Maîtrise","inFormula":false,"levels":[{"value":1,"label":"Faible"},{"value":2,"label":"Modérée"},{"value":3,"label":"Forte"},{"value":4,"label":"Très forte"}]}]',
         '[{"level":"faible","label":"Faible","min":1,"color":"#2E7D32"},{"level":"moyen","label":"Moyen","min":4,"color":"#F9A825"},
           {"level":"eleve","label":"Élevé","min":8,"color":"#EF6C00"},{"level":"critique","label":"Critique","min":12,"color":"#C62828"}]')
    ON CONFLICT (tenant_id, code) DO NOTHING;
    SELECT id INTO sc FROM risk_scale WHERE tenant_id = t AND code = 'DEFAUT';

    INSERT INTO record (id, tenant_id, type_code, reference, status, title, description, site_id, owner_id, due_date,
                        data, created_at, created_by) VALUES
        (a_risk, t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 1)::text, 4, '0'), 'EN_COURS',
         'Capoter la sortie du four tunnel et installer un arrêt d''urgence', NULL, usine, u_q, current_date + 20,
         jsonb_build_object('actionType', 'maitrise', 'origine', 'risque', 'riskId', r_four, 'riskReference',
         'RSK-0001'), now() - interval '5 days', u_q),
        (a_reg, t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 2)::text, 4, '0'), 'A_FAIRE',
         'Mise en conformité : Loi 65-99 art. 281', 'Mettre à jour le document unique et la formation sécurité.', usine,
         u_q, current_date + 30, jsonb_build_object('actionType', 'conformite', 'origine', 'veille'), now(), u_q);
    INSERT INTO record_sequence (tenant_id, prefix, year, last_value) VALUES (t, 'NC', y, n_nc + 2),
        (t, 'ACT', y, n_act + 2)
    ON CONFLICT (tenant_id, prefix, year) DO UPDATE SET last_value = greatest(record_sequence.last_value,
        excluded.last_value);

    INSERT INTO risk (id, tenant_id, reference, type, site_id, scale_id, work_unit, process, hazard, situation,
                      existing_measures, gross, gross_score, gross_level, residual, residual_score, residual_level,
                      reassessment_date, created_by) VALUES
        (r_four, t, 'RSK-0001', 'sst', usine, sc, 'Ligne biscuits — four tunnel', 'Production',
         'Brûlure au contact des parois et de la sortie du four tunnel', 'Dégagement d''un bourrage four en marche',
         'Gants anti-chaleur, consigne affichée', '{"gravite":4,"probabilite":3,"maitrise":2}', 12, 'critique',
         NULL, NULL, NULL, current_date + 90, u_q),
        (gen_random_uuid(), t, 'RSK-0002', 'sst', usine, sc, 'Ligne biscuits — conditionnement', 'Production',
         'Troubles musculosquelettiques (gestes répétitifs)', 'Mise en carton manuelle 8 h par jour',
         'Rotation des postes toutes les 2 h', '{"gravite":2,"probabilite":4}', 8, 'eleve',
         '{"gravite":2,"probabilite":2}', 4, 'moyen', current_date + 180, u_q),
        (gen_random_uuid(), t, 'RSK-0003', 'sst', usine, sc, 'Magasin matières', 'Logistique',
         'Collision chariot élévateur / piéton', 'Croisement dans l''allée centrale', 'Marquage au sol, CACES',
         '{"gravite":4,"probabilite":2}', 8, 'eleve', '{"gravite":4,"probabilite":1}', 4, 'moyen', current_date + 120,
         u_q),
        (gen_random_uuid(), t, 'RSK-0004', 'environnement', usine, sc, 'Station de lavage', 'Nettoyage',
         'Rejet d''eaux de lavage chargées en matières grasses', 'Lavage des cuves en fin de poste', 'Bac dégraisseur',
         '{"gravite":3,"probabilite":2}', 6, 'moyen', NULL, NULL, NULL, current_date + 180, u_q),
        (gen_random_uuid(), t, 'RSK-0005', 'qualite', usine, sc, NULL, 'Réception matières',
         'Contamination de la farine par l''humidité', 'Livraison sous la pluie', 'Contrôle humidité à réception',
         '{"gravite":3,"probabilite":2}', 6, 'moyen', NULL, NULL, NULL, current_date + 180, u_q),
        (gen_random_uuid(), t, 'RSK-0006', 'opportunite', depot, sc, NULL, 'Logistique',
         'Mutualiser les tournées Tanger avec un second client', 'Taux de remplissage camion de 60 %', NULL,
         '{"gravite":3,"probabilite":3}', 9, 'eleve', NULL, NULL, NULL, current_date + 60, u_q);
    INSERT INTO risk_rating_history (tenant_id, risk_id, kind, ratings, score, level, actor_id)
    SELECT t, id, 'gross', gross, gross_score, gross_level, u_q FROM risk WHERE tenant_id = t;
    INSERT INTO risk_action (tenant_id, risk_id, action_id) VALUES (t, r_four, a_risk);

    -- ------------------------------------------------------------------ veille réglementaire (résumés maison)
    FOR rec IN SELECT * FROM (VALUES
        ('Loi 65-99 relative au Code du travail, art. 281 à 291', 'Code du travail — hygiène et sécurité des salariés',
         'sst', 'Maroc', '2003-09-11', '2004-06-08', 'L''employeur prend les mesures nécessaires pour préserver la santé et la sécurité des salariés et tient les locaux dans un état de propreté.', 'non_conforme'),
        ('Loi 28-00 relative à la gestion des déchets et à leur élimination', 'Gestion des déchets',
         'environnement', 'Maroc', '2006-11-22', '2006-12-07', 'Le producteur de déchets assure leur tri, leur stockage et leur élimination par une filière autorisée.', 'conforme'),
        ('Loi 11-03 relative à la protection et à la mise en valeur de l''environnement', 'Protection de l''environnement',
         'environnement', 'Maroc', '2003-05-12', '2003-06-19', 'Principes généraux de prévention des pollutions et de réparation des dommages.', 'partiel'),
        ('Loi 28-07 relative à la sécurité sanitaire des produits alimentaires', 'Sécurité sanitaire des aliments',
         'produit', 'Maroc', '2010-02-11', '2011-03-01', 'L''exploitant garantit la sécurité sanitaire des produits et leur traçabilité à toutes les étapes.', 'conforme'),
        ('Loi 10-95 sur l''eau', 'Loi sur l''eau', 'environnement', 'Maroc', '1995-08-16', '1995-09-20',
         'Les rejets d''eaux usées dans le domaine public hydraulique sont soumis à autorisation.', 'conforme'),
        ('Décret 2-12-172 relatif aux prescriptions des installations électriques', 'Installations électriques',
         'sst', 'Maroc', '2012-05-04', '2012-07-01', 'Vérification périodique des installations électriques par un organisme agréé.', NULL),
        ('Règlement (CE) 852/2004 relatif à l''hygiène des denrées alimentaires', 'Hygiène des denrées alimentaires',
         'produit', 'UE', '2004-04-29', '2006-01-01', 'Mise en place de procédures fondées sur les principes HACCP pour les exportations.', 'conforme'),
        ('Règlement (CE) 178/2002 établissant les principes de la législation alimentaire', 'Législation alimentaire générale',
         'produit', 'UE', '2002-01-28', '2002-02-21', 'Traçabilité un pas en amont, un pas en aval ; retrait et rappel des produits.', 'conforme'),
        ('Code du travail (France), art. R4121-1', 'Document unique d''évaluation des risques', 'sst', 'France',
         '2008-03-07', '2008-05-01', 'Référence pour la filiale française : l''évaluation des risques est transcrite dans un document unique.', NULL),
        ('Loi 09-08 relative à la protection des personnes physiques à l''égard des données personnelles',
         'Protection des données personnelles', 'qualite', 'Maroc', '2009-02-18', '2009-02-23',
         'Déclaration à la CNDP des traitements de données et information des personnes.', 'conforme')
    ) AS v(ref, title, domain, jur, pub, eff, summary, result) LOOP
        k := k + 1;
        INSERT INTO regulation (tenant_id, reference, title, domain, jurisdiction, published_on, effective_on, summary,
                                status, review_months)
        VALUES (t, rec.ref, rec.title, rec.domain, rec.jur, rec.pub::date, rec.eff::date, rec.summary,
                CASE WHEN rec.jur = 'France' THEN 'a_venir' ELSE 'en_vigueur' END, 12)
        ON CONFLICT (tenant_id, reference) DO NOTHING RETURNING id INTO reg;
        IF reg IS NULL THEN
            CONTINUE;
        END IF;
        INSERT INTO regulation_requirement (tenant_id, regulation_id, code, text, position)
        VALUES (t, reg, 'E1', 'Exigence principale : ' || rec.summary, 0) RETURNING id INTO req;
        INSERT INTO regulation_applicability (tenant_id, requirement_id, site_id, applicable, justification) VALUES
            (t, req, usine, rec.jur <> 'France', CASE WHEN rec.jur = 'France' THEN 'Hors périmètre du site' ELSE
             'Site de production concerné' END),
            (t, req, depot, rec.domain IN ('sst', 'environnement'), 'Entrepôt : selon l''activité');
        IF rec.result IS NOT NULL THEN
            INSERT INTO regulation_evaluation (tenant_id, regulation_id, site_id, result, evidence, comment, next_due,
                                               action_id, evaluated_by, evaluated_at)
            VALUES (t, reg, usine, rec.result, 'Revue de conformité annuelle', CASE rec.result
                    WHEN 'non_conforme' THEN 'Document unique non mis à jour depuis 2024'
                    WHEN 'partiel' THEN 'Plan de prévention des pollutions à compléter' END,
                    CASE WHEN k = 5 THEN current_date + 10 ELSE current_date + 300 END,
                    CASE WHEN rec.result = 'non_conforme' THEN a_reg END, u_q, now() - interval '20 days');
            IF rec.result = 'non_conforme' THEN
                UPDATE record SET data = data || jsonb_build_object('regulationId', reg, 'regulationReference', rec.ref)
                WHERE id = a_reg;
            END IF;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------ évaluation clients et fournisseurs
    INSERT INTO evaluation_grid (id, tenant_id, code, label, partner_type, scale_max, criteria, classes,
                                 improvement_classes) VALUES
        (g_fou, t, 'FOURNISSEUR', 'Évaluation fournisseur trimestrielle', 'fournisseur', 4,
         '[{"code":"qualite_service","label":"Qualité de service","weight":3.0,"auto":null,"bands":null},
           {"code":"reactivite","label":"Réactivité","weight":2.0,"auto":null,"bands":null},
           {"code":"nc","label":"Non-conformités","weight":2.0,"auto":"nc_count","bands":[{"max":0.0,"score":4},{"max":1.0,"score":3},{"max":3.0,"score":1},{"max":null,"score":0}]},
           {"code":"lots_refuses","label":"Lots refusés","weight":2.0,"auto":"lots_refused_pct","bands":[{"max":0.0,"score":4},{"max":5.0,"score":2},{"max":null,"score":0}]},
           {"code":"retards","label":"Retards de livraison","weight":1.0,"auto":"late_deliveries","bands":[{"max":0.0,"score":4},{"max":2.0,"score":2},{"max":null,"score":0}]}]',
         '[{"code":"A","label":"Agréé","min":80.0},{"code":"B","label":"Sous surveillance","min":60.0},{"code":"C","label":"Refusé","min":0.0}]',
         ARRAY['C']),
        (g_cli, t, 'CLIENT', 'Satisfaction client', 'client', 5,
         '[{"code":"accueil","label":"Accueil et écoute","weight":1.0,"auto":null,"bands":null},
           {"code":"delais","label":"Respect des délais","weight":2.0,"auto":null,"bands":null},
           {"code":"qualite_produit","label":"Qualité des produits","weight":3.0,"auto":null,"bands":null},
           {"code":"reclamations","label":"Réclamations","weight":2.0,"auto":"complaints","bands":[{"max":0.0,"score":5},{"max":2.0,"score":3},{"max":null,"score":0}]}]',
         '[{"code":"A","label":"Agréé","min":80.0},{"code":"B","label":"Sous surveillance","min":60.0},{"code":"C","label":"Refusé","min":0.0}]',
         ARRAY['C'])
    ON CONFLICT (tenant_id, code) DO NOTHING;
    SELECT id INTO g_fou FROM evaluation_grid WHERE tenant_id = t AND code = 'FOURNISSEUR';

    -- trimestre précédent : notes saisies (historique du scorecard)
    INSERT INTO partner_evaluation (tenant_id, partner_id, grid_id, period, period_start, period_end, status, scores,
                                    indicators, global_score, class, comment, evaluated_by, evaluated_at) VALUES
        (t, f_mou, g_fou, p_code, p_start, (p_start + interval '3 months' - interval '1 day')::date, 'terminee',
         '{"qualite_service":3,"reactivite":3,"nc":4,"lots_refuses":4,"retards":2}',
         '{"ncCount":0,"lotsReceived":6,"lotsRefused":0,"lotsRefusedPct":0.0,"lotsDerogation":0,"lateDeliveries":1}',
         85.0, 'A', 'Bon trimestre', u_q, now() - interval '60 days'),
        (t, f_suc, g_fou, p_code, p_start, (p_start + interval '3 months' - interval '1 day')::date, 'terminee',
         '{"qualite_service":3,"reactivite":2,"nc":4,"lots_refuses":4,"retards":4}',
         '{"ncCount":0,"lotsReceived":4,"lotsRefused":0,"lotsRefusedPct":0.0,"lotsDerogation":0,"lateDeliveries":0}',
         82.5, 'A', NULL, u_q, now() - interval '60 days');

    -- trimestre en cours : lot refusé, livré en retard, NC liée (ci-dessus) → indicateurs automatiques
    INSERT INTO lot (id, tenant_id, article_id, numero, supplier_id, supplier_lot, quantity, unit, dlc, site_id,
                     quality_status, created_by, expected_date)
    VALUES (l_new, t, a_far, 'LF-DEMO-M4', f_mou, 'MS-4410', 2000, 'kg', current_date + 180, usine, 'REFUSE', u_q,
            current_date - 2)
    ON CONFLICT DO NOTHING;
    INSERT INTO lot_status_history (tenant_id, lot_id, from_status, to_status, reason, nc_id, actor_id) VALUES
        (t, l_new, NULL, 'EN_ATTENTE', 'Création du lot', NULL, u_q),
        (t, l_new, 'EN_ATTENTE', 'REFUSE', 'Humidité hors spécification', nc_fou, u_q);

    INSERT INTO evaluation_campaign (id, tenant_id, grid_id, period, period_start, period_end, created_by)
    VALUES (camp, t, g_fou, q_code, q_start, (q_start + interval '3 months' - interval '1 day')::date, u_q);
    INSERT INTO partner_evaluation (tenant_id, partner_id, grid_id, campaign_id, period, period_start, period_end,
                                    status)
    SELECT t, p.id, g_fou, camp, q_code, q_start, (q_start + interval '3 months' - interval '1 day')::date, 'a_faire'
    FROM partner p WHERE p.tenant_id = t AND p.type = 'fournisseur'
    ON CONFLICT DO NOTHING;
END
$$;
