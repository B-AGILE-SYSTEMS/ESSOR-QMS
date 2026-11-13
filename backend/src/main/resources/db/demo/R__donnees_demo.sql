-- Données de démonstration (profil Spring "demo" uniquement). Idempotent.
-- Comptes préparés "pending:<username>" : rattachés au compte Keycloak de même nom à la première connexion.

DO $$
DECLARE
    t      uuid := '00000000-0000-0000-0000-00000000d001';
    siege  uuid;
    usine  uuid := '00000000-0000-0000-0000-0000000051a1';
    depot  uuid := '00000000-0000-0000-0000-0000000051a2';
    u_admin   uuid;
    u_qualite uuid;
    u_terrain uuid;
    y      int := extract(year FROM current_date);
    n_nc   int;
    n_act  int;
BEGIN
    INSERT INTO site (id, tenant_id, code, name) VALUES
        (usine, t, 'USINE-CASA', 'Usine de Casablanca'),
        (depot, t, 'ENTREPOT-TNG', 'Entrepôt de Tanger')
    ON CONFLICT DO NOTHING;
    SELECT id INTO siege FROM site WHERE tenant_id = t AND code = 'SIEGE';

    INSERT INTO app_user (tenant_id, subject, username, display_name, roles, email)
    SELECT t, 'pending:' || v.username, v.username, v.name, v.roles, v.email
    FROM (VALUES ('admin', 'Admin Démo', ARRAY['ADMIN', 'QUALITY_MANAGER'], 'admin@essor.local'),
                 ('qualite', 'Qualité Démo', ARRAY['QUALITY_MANAGER'], 'qualite@essor.local'),
                 ('terrain', 'Terrain Démo', ARRAY['FIELD_USER', 'AUDITOR'], 'terrain@essor.local'))
         AS v(username, name, roles, email)
    WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t AND u.username = v.username);

    SELECT id INTO u_admin FROM app_user WHERE tenant_id = t AND username = 'admin' LIMIT 1;
    SELECT id INTO u_qualite FROM app_user WHERE tenant_id = t AND username = 'qualite' LIMIT 1;
    SELECT id INTO u_terrain FROM app_user WHERE tenant_id = t AND username = 'terrain' LIMIT 1;

    INSERT INTO user_site (tenant_id, user_id, site_id)
    SELECT t, u.id, s.id FROM app_user u CROSS JOIN site s WHERE u.tenant_id = t AND s.tenant_id = t
    ON CONFLICT DO NOTHING;

    IF EXISTS (SELECT 1 FROM record WHERE id = '00000000-0000-0000-0000-00000000c001') THEN
        RETURN; -- déjà chargées
    END IF;
    -- références à la suite de la numérotation existante du tenant
    SELECT coalesce(max(last_value), 0) INTO n_nc FROM record_sequence WHERE tenant_id = t AND prefix = 'NC' AND year = y;
    SELECT coalesce(max(last_value), 0) INTO n_act FROM record_sequence WHERE tenant_id = t AND prefix = 'ACT' AND year = y;

    INSERT INTO record (id, tenant_id, type_code, reference, status, title, description, severity, site_id,
                        owner_id, due_date, parent_id, data, created_at, created_by) VALUES
     ('00000000-0000-0000-0000-00000000c001', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 1)::text, 4, '0'), 'DECLAREE',
      'Fuite d''huile sur la presse P4', 'Flaque d''huile constatée sous la presse lors de la ronde.', 'majeure',
      usine, NULL, NULL, NULL, '{"cost": 1200}', now() - interval '1 day', u_terrain),
     ('00000000-0000-0000-0000-00000000c002', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 2)::text, 4, '0'), 'QUALIFIEE',
      'Étiquetage non conforme lot 2611', 'Étiquettes sans numéro de lot sur 40 cartons.', 'mineure',
      depot, u_qualite, current_date + 10, NULL, '{"cost": 300}', now() - interval '3 days', u_terrain),
     ('00000000-0000-0000-0000-00000000c003', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 3)::text, 4, '0'), 'ANALYSE',
      'Réclamation client : rayures sur pièces', 'Le client signale des rayures sur 5 % des pièces livrées.', 'majeure',
      usine, u_qualite, current_date + 7, NULL,
      '{"analyse5p": ["Rayures au déballage", "Calage insuffisant"], "cost": 4500}', now() - interval '6 days', u_qualite),
     ('00000000-0000-0000-0000-00000000c004', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 4)::text, 4, '0'), 'ACTIONS',
      'Extincteur périmé zone B', 'Contrôle annuel dépassé sur 2 extincteurs.', 'critique',
      depot, u_admin, current_date + 3, NULL,
      '{"causeRacine": "Planning de contrôle non suivi", "cost": 800}', now() - interval '12 days', u_qualite),
     ('00000000-0000-0000-0000-00000000c005', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 5)::text, 4, '0'), 'VERIFICATION',
      'Température chambre froide hors tolérance', 'Relevé à 9 °C pendant 2 h.', 'majeure',
      siege, u_qualite, current_date + 15, NULL,
      '{"causeRacine": "Joint de porte usé", "cost": 2500}', now() - interval '20 days', u_terrain),
     ('00000000-0000-0000-0000-00000000c006', t, 'NC', 'NC-' || y || '-' || lpad((n_nc + 6)::text, 4, '0'), 'CLOTUREE',
      'EPI manquants au poste de soudure', 'Gants de soudure absents au poste 3.', 'mineure',
      usine, u_qualite, current_date - 5, NULL,
      '{"causeRacine": "Réapprovisionnement non déclenché", "cost": 150}', now() - interval '40 days', u_terrain),
     -- actions
     ('00000000-0000-0000-0000-00000000a001', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 1)::text, 4, '0'), 'EN_COURS',
      'Remplacer les extincteurs périmés', NULL, NULL, depot, u_terrain, current_date - 1,
      '00000000-0000-0000-0000-00000000c004', '{"actionType": "curative"}', now() - interval '10 days', u_qualite),
     ('00000000-0000-0000-0000-00000000a002', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 2)::text, 4, '0'), 'A_FAIRE',
      'Intégrer les extincteurs au planning GMAO', NULL, NULL, depot, u_qualite, current_date + 2,
      '00000000-0000-0000-0000-00000000c004', '{"actionType": "corrective"}', now() - interval '10 days', u_qualite),
     ('00000000-0000-0000-0000-00000000a003', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 3)::text, 4, '0'), 'FAITE',
      'Remplacer le joint de porte', NULL, NULL, siege, u_terrain, current_date - 8,
      '00000000-0000-0000-0000-00000000c005', '{"actionType": "corrective"}', now() - interval '18 days', u_qualite),
     ('00000000-0000-0000-0000-00000000a004', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 4)::text, 4, '0'), 'FAITE',
      'Ajouter le joint au plan de maintenance préventive', NULL, NULL, siege, u_qualite, current_date - 4,
      '00000000-0000-0000-0000-00000000c005', '{"actionType": "preventive"}', now() - interval '18 days', u_qualite),
     ('00000000-0000-0000-0000-00000000a005', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 5)::text, 4, '0'), 'VERIFIEE',
      'Réapprovisionner les gants de soudure', NULL, NULL, usine, u_terrain, current_date - 30,
      '00000000-0000-0000-0000-00000000c006', '{"actionType": "curative"}', now() - interval '38 days', u_qualite),
     ('00000000-0000-0000-0000-00000000a006', t, 'ACTION', 'ACT-' || y || '-' || lpad((n_act + 6)::text, 4, '0'), 'VERIFIEE',
      'Seuil d''alerte de stock EPI', NULL, NULL, usine, u_qualite, current_date - 20,
      '00000000-0000-0000-0000-00000000c006', '{"actionType": "preventive"}', now() - interval '38 days', u_qualite)
    ;

    INSERT INTO record_sequence (tenant_id, prefix, year, last_value) VALUES (t, 'NC', y, n_nc + 6), (t, 'ACT', y, n_act + 6)
    ON CONFLICT (tenant_id, prefix, year)
        DO UPDATE SET last_value = greatest(record_sequence.last_value, excluded.last_value);
END
$$;
