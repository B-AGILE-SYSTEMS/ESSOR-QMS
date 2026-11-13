-- Données de démonstration M3b (profil "demo"). Idempotent (identifiants fixes, ON CONFLICT DO NOTHING).
-- Scénario : lot fournisseur de farine reçu, contrôlé (ISO 2859-1, NQA 2,5) et libéré ; consommé par l'OF-2026-0142
-- dont le lot fini de biscuits est suivi en cours (carte X̄/R sur le poids) puis libéré et expédié à 2 clients.
-- Démo du rappel : GET /api/lots/00000000-0000-0000-0000-00000000b301/recall

DO $$
DECLARE
    t       uuid := '00000000-0000-0000-0000-00000000d001';
    usine   uuid := '00000000-0000-0000-0000-0000000051a1';
    u_q     uuid;
    a_far   uuid := '00000000-0000-0000-0000-00000000b101';
    a_suc   uuid := '00000000-0000-0000-0000-00000000b102';
    a_beu   uuid := '00000000-0000-0000-0000-00000000b103';
    a_emb   uuid := '00000000-0000-0000-0000-00000000b104';
    a_bis   uuid := '00000000-0000-0000-0000-00000000b105';
    a_car   uuid := '00000000-0000-0000-0000-00000000b106';
    f_mou   uuid := '00000000-0000-0000-0000-00000000b201';
    f_suc   uuid := '00000000-0000-0000-0000-00000000b202';
    f_emb   uuid := '00000000-0000-0000-0000-00000000b203';
    c_1     uuid := '00000000-0000-0000-0000-00000000b211';
    c_2     uuid := '00000000-0000-0000-0000-00000000b212';
    c_3     uuid := '00000000-0000-0000-0000-00000000b213';
    c_4     uuid := '00000000-0000-0000-0000-00000000b214';
    l_far   uuid := '00000000-0000-0000-0000-00000000b301';
    l_suc   uuid := '00000000-0000-0000-0000-00000000b302';
    l_bis   uuid := '00000000-0000-0000-0000-00000000b303';
    p_rec   uuid := '00000000-0000-0000-0000-00000000b401';
    p_enc   uuid := '00000000-0000-0000-0000-00000000b402';
    p_sor   uuid := '00000000-0000-0000-0000-00000000b403';
    i_rec   uuid := '00000000-0000-0000-0000-00000000b501';
    i_sor   uuid := '00000000-0000-0000-0000-00000000b502';
    d0      timestamptz := date_trunc('day', now()) - interval '3 days';
    means   numeric[] := ARRAY[25.1, 24.9, 25.0, 25.2, 24.8, 25.0, 25.1, 24.9, 25.0, 25.6];
    i       int;
    insp    uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM site WHERE id = usine) THEN
        RETURN;
    END IF;
    SELECT id INTO u_q FROM app_user WHERE tenant_id = t AND username = 'qualite';

    INSERT INTO article (id, tenant_id, code, label, family, unit, tracking, has_dlc) VALUES
        (a_far, t, 'MP-FARINE', 'Farine de blé T55', 'MATIERE', 'kg', 'lot', true),
        (a_suc, t, 'MP-SUCRE', 'Sucre semoule', 'MATIERE', 'kg', 'lot', false),
        (a_beu, t, 'MP-BEURRE', 'Beurre doux', 'MATIERE', 'kg', 'lot', true),
        (a_emb, t, 'EMB-SACHET', 'Sachet 200 g imprimé', 'EMBALLAGE', 'u', 'lot', false),
        (a_bis, t, 'PF-BISCUIT', 'Biscuit sablé 200 g', 'PRODUIT_FINI', 'u', 'lot', true),
        (a_car, t, 'PF-CARTON', 'Carton de 24 sachets', 'PRODUIT_FINI', 'u', 'serie', false)
    ON CONFLICT DO NOTHING;

    INSERT INTO partner (id, tenant_id, code, name, type) VALUES
        (f_mou, t, 'FOU-MOULINS', 'Moulins du Souss', 'fournisseur'),
        (f_suc, t, 'FOU-SUCRERIE', 'Sucrerie de l''Atlas', 'fournisseur'),
        (f_emb, t, 'FOU-EMBAL', 'Emballages du Nord', 'fournisseur'),
        (c_1, t, 'CLI-MARJANE', 'Hypermarché Casablanca', 'client'),
        (c_2, t, 'CLI-EPICERIE', 'Épiceries de Rabat', 'client'),
        (c_3, t, 'CLI-HOTEL', 'Hôtellerie Marrakech', 'client'),
        (c_4, t, 'CLI-EXPORT', 'Distributeur export', 'client')
    ON CONFLICT DO NOTHING;

    INSERT INTO control_plan (id, tenant_id, code, version, label, point, target_type, target_id, target_family,
                              characteristics, sampling, frequency, created_by) VALUES
        (p_rec, t, 'PC-REC-FARINE', 1, 'Réception farine', 'RECEPTION', 'article', a_far, NULL,
         '[{"code":"HUM","libelle":"Humidité","type":"mesure","nominal":14.0,"tolMin":12.0,"tolMax":15.5,"unite":"%","instrumentRequis":"Dessiccateur","criticite":"majeure"},
           {"code":"CERT","libelle":"Certificat d''analyse (CoA)","type":"document","nominal":null,"tolMin":null,"tolMax":null,"unite":null,"instrumentRequis":null,"criticite":"critique"},
           {"code":"ASPECT","libelle":"Aspect et intégrité des sacs","type":"attribut","nominal":null,"tolMin":null,"tolMax":null,"unite":null,"instrumentRequis":null,"criticite":"mineure"}]',
         '{"mode":"iso2859","taille":null,"nqa":"2.5","niveau":"II"}', NULL, u_q),
        (p_enc, t, 'PC-ENC-BISCUIT', 1, 'En-cours biscuit (poids sachet)', 'PRODUCTION_ENCOURS', 'article', a_bis,
         NULL,
         '[{"code":"POIDS","libelle":"Poids net sachet","type":"mesure","nominal":25.0,"tolMin":24.0,"tolMax":26.0,"unite":"g","instrumentRequis":"Balance","criticite":"majeure"}]',
         '{"mode":"fixe","taille":5,"nqa":null,"niveau":null}', '{"pieces":500,"minutes":30}', u_q),
        (p_sor, t, 'PC-SOR-BISCUIT', 1, 'Libération lot fini biscuit', 'PRODUCTION_SORTIE', 'famille', NULL,
         'PRODUIT_FINI',
         '[{"code":"MARQUAGE","libelle":"Marquage lot et DLC","type":"attribut","nominal":null,"tolMin":null,"tolMax":null,"unite":null,"instrumentRequis":null,"criticite":"critique"},
           {"code":"POIDS","libelle":"Poids net sachet","type":"mesure","nominal":25.0,"tolMin":24.0,"tolMax":26.0,"unite":"g","instrumentRequis":"Balance","criticite":"majeure"}]',
         '{"mode":"fixe","taille":13,"nqa":null,"niveau":null}', NULL, u_q)
    ON CONFLICT DO NOTHING;

    INSERT INTO lot (id, tenant_id, article_id, numero, supplier_id, supplier_lot, quantity, unit, dlc, site_id,
                     quality_status, created_by, created_at, updated_at) VALUES
        (l_far, t, a_far, 'LF-2026-0311', f_mou, 'MS-88412', 1000, 'kg', current_date + 180, usine, 'LIBERE',
         u_q, d0, d0 + interval '2 hours'),
        (l_suc, t, a_suc, 'LF-2026-0312', f_suc, 'SA-1203', 500, 'kg', NULL, usine, 'LIBERE', u_q, d0, d0),
        (l_bis, t, a_bis, 'PF-2026-0142', NULL, NULL, 4000, 'u', current_date + 120, usine, 'LIBERE', u_q,
         d0 + interval '1 day', d0 + interval '2 days')
    ON CONFLICT DO NOTHING;

    IF EXISTS (SELECT 1 FROM lot_status_history WHERE lot_id = l_far) THEN
        RETURN; -- scénario déjà joué
    END IF;

    INSERT INTO lot_status_history (tenant_id, lot_id, from_status, to_status, reason, actor_id, at) VALUES
        (t, l_far, NULL, 'EN_ATTENTE', 'Création du lot', u_q, d0),
        (t, l_suc, NULL, 'EN_ATTENTE', 'Création du lot', u_q, d0),
        (t, l_suc, 'EN_ATTENTE', 'LIBERE', 'Libération sur certificat fournisseur', u_q, d0 + interval '1 hour'),
        (t, l_bis, NULL, 'EN_ATTENTE', 'Création du lot', u_q, d0 + interval '1 day');

    -- Contrôle de réception : lot de 1000 kg -> lettre J, 80 prélèvements, Ac 5 / Re 6.
    INSERT INTO inspection (id, tenant_id, lot_id, control_plan_id, point, site_id, sampling, sample_size, decision,
                            comment, created_by, created_at, decided_by, decided_at)
    VALUES (i_rec, t, l_far, p_rec, 'RECEPTION', usine,
            '{"mode":"iso2859","lotSize":1000,"niveau":"II","nqa":"2.5","codeLetter":"J","planLetter":"J","sampleSize":80,"ac":5,"re":6}',
            80, 'LIBERE', 'Conforme', u_q, d0 + interval '1 hour', u_q, d0 + interval '2 hours');
    INSERT INTO inspection_result (tenant_id, inspection_id, characteristic, values_json, ok, instrument,
                                   plan_version, site_id, author_id, at) VALUES
        (t, i_rec, 'HUM', '[14.1, 13.8, 14.3]', true, 'DES-02', 1, usine, u_q, d0 + interval '90 minutes'),
        (t, i_rec, 'CERT', '[]', true, NULL, 1, usine, u_q, d0 + interval '90 minutes'),
        (t, i_rec, 'ASPECT', '[]', true, NULL, 1, usine, u_q, d0 + interval '90 minutes');
    INSERT INTO lot_status_history (tenant_id, lot_id, from_status, to_status, reason, inspection_id, actor_id, at)
    VALUES (t, l_far, 'EN_ATTENTE', 'LIBERE', 'Contrôle PC-REC-FARINE v1 : Conforme', i_rec, u_q,
            d0 + interval '2 hours');

    -- OF-2026-0142 : farine et sucre consommés par le lot fini.
    INSERT INTO lot_link (tenant_id, source_lot_id, kind, target_lot_id, quantity, reference, created_by, created_at)
    VALUES (t, l_far, 'consumed_by', l_bis, 800, 'OF-2026-0142', u_q, d0 + interval '1 day'),
           (t, l_suc, 'consumed_by', l_bis, 300, 'OF-2026-0142', u_q, d0 + interval '1 day');

    -- En-cours : 10 sous-groupes de 5 sachets (le dernier dérive au-dessus de la limite haute).
    FOR i IN 1..array_length(means, 1) LOOP
        insp := ('00000000-0000-0000-0000-0000000b6' || lpad(i::text, 3, '0'))::uuid;
        INSERT INTO inspection (id, tenant_id, lot_id, control_plan_id, point, site_id, sampling, sample_size,
                                created_by, created_at)
        VALUES (insp, t, l_bis, p_enc, 'PRODUCTION_ENCOURS', usine, '{"mode":"fixe","lotSize":4000,"sampleSize":5}',
                5, u_q, d0 + interval '1 day' + i * interval '30 minutes');
        INSERT INTO inspection_result (tenant_id, inspection_id, characteristic, values_json, ok, instrument,
                                       plan_version, site_id, author_id, at)
        VALUES (t, insp, 'POIDS', jsonb_build_array(means[i] - 0.2, means[i] - 0.1, means[i], means[i] + 0.1,
                                                    means[i] + 0.2),
                true, 'BAL-07', 1, usine, u_q, d0 + interval '1 day' + i * interval '30 minutes');
    END LOOP;

    -- Contrôle de sortie puis libération.
    INSERT INTO inspection (id, tenant_id, lot_id, control_plan_id, point, site_id, sampling, sample_size, decision,
                            comment, created_by, created_at, decided_by, decided_at)
    VALUES (i_sor, t, l_bis, p_sor, 'PRODUCTION_SORTIE', usine, '{"mode":"fixe","lotSize":4000,"sampleSize":13}',
            13, 'LIBERE', 'Lot conforme', u_q, d0 + interval '2 days', u_q, d0 + interval '2 days');
    INSERT INTO inspection_result (tenant_id, inspection_id, characteristic, values_json, ok, instrument,
                                   plan_version, site_id, author_id, at) VALUES
        (t, i_sor, 'MARQUAGE', '[]', true, NULL, 1, usine, u_q, d0 + interval '2 days'),
        (t, i_sor, 'POIDS', '[25.0, 25.1, 24.9]', true, 'BAL-07', 1, usine, u_q, d0 + interval '2 days');
    INSERT INTO lot_status_history (tenant_id, lot_id, from_status, to_status, reason, inspection_id, actor_id, at)
    VALUES (t, l_bis, 'EN_ATTENTE', 'LIBERE', 'Contrôle PC-SOR-BISCUIT v1 : Lot conforme', i_sor, u_q,
            d0 + interval '2 days');

    -- Expéditions à 2 clients (BL et SSCC dans la référence).
    INSERT INTO lot_link (tenant_id, source_lot_id, kind, partner_id, quantity, reference, created_by, created_at)
    VALUES (t, l_bis, 'shipped_to', c_1, 1500, 'BL-2026-0501 SSCC-361234560000000017', u_q,
            d0 + interval '2 days 3 hours'),
           (t, l_bis, 'shipped_to', c_2, 900, 'BL-2026-0502 SSCC-361234560000000024', u_q,
            d0 + interval '2 days 4 hours');
END $$;
