-- M1 : moteur générique de fiches (types, workflow JSON, fiches, numérotation) et données d'identité utiles
-- au workflow (rôles et e-mail mémorisés à chaque connexion).

ALTER TABLE app_user ADD COLUMN roles text[] NOT NULL DEFAULT '{}';
ALTER TABLE app_user ADD COLUMN email varchar(320);

-- Modèles de types de fiche livrés par défaut (non tenant-scopés, lecture seule pour l'application).
-- Copiés dans chaque tenant (record_type) au premier usage ; l'exploitant modifie ensuite sa copie.
CREATE TABLE record_type_template (
    code         varchar(30)  PRIMARY KEY,
    label        varchar(100) NOT NULL,
    ref_prefix   varchar(10)  NOT NULL,
    field_schema jsonb        NOT NULL DEFAULT '{}',
    workflow     jsonb        NOT NULL
);

-- Types de fiche du tenant. Tenant-scopé.
CREATE TABLE record_type (
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    code         varchar(30)  NOT NULL,
    label        varchar(100) NOT NULL,
    ref_prefix   varchar(10)  NOT NULL,
    field_schema jsonb        NOT NULL DEFAULT '{}',
    workflow     jsonb        NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, code)
);

-- Compteur de références par tenant, préfixe et année (NC-2026-0001). Tenant-scopé.
CREATE TABLE record_sequence (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    prefix     varchar(10) NOT NULL,
    year       int         NOT NULL,
    last_value int         NOT NULL,
    PRIMARY KEY (tenant_id, prefix, year)
);

-- Fiches (NC, actions, ...). Champs communs en colonnes, champs personnalisés en JSONB. Tenant-scopé.
CREATE TABLE record (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    type_code   varchar(30)  NOT NULL,
    reference   varchar(30)  NOT NULL,
    status      varchar(30)  NOT NULL,
    title       varchar(200) NOT NULL,
    description text,
    severity    varchar(10) CHECK (severity IN ('mineure', 'majeure', 'critique')),
    site_id     uuid,
    owner_id    uuid,
    due_date    date,
    parent_id   uuid,
    data        jsonb        NOT NULL DEFAULT '{}',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference),
    FOREIGN KEY (tenant_id, type_code) REFERENCES record_type (tenant_id, code),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_id) REFERENCES app_user (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES record (tenant_id, id)
);
CREATE INDEX idx_record_list ON record (tenant_id, type_code, status, created_at DESC);
CREATE INDEX idx_record_owner ON record (tenant_id, owner_id, due_date);
CREATE INDEX idx_record_parent ON record (tenant_id, parent_id);
CREATE INDEX idx_audit_log_entity ON audit_log (tenant_id, entity_type, entity_id, occurred_at);

ALTER TABLE record_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE record_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record_type
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE record_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE record_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record_sequence
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE record ENABLE ROW LEVEL SECURITY;
ALTER TABLE record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT ON record_type_template TO essor_app;
GRANT SELECT, INSERT, UPDATE ON record_type, record_sequence, record TO essor_app;

-- Workflow NC par défaut : DECLAREE -> QUALIFIEE -> ANALYSE -> ACTIONS -> VERIFICATION -> CLOTUREE (+ REJETEE).
-- "childrenIn" : toutes les fiches enfants (actions) doivent être dans l'un de ces statuts.
INSERT INTO record_type_template (code, label, ref_prefix, field_schema, workflow) VALUES
('NC', 'Non-conformité', 'NC',
 '{"fields":[{"code":"causeRacine","label":"Cause racine","type":"text"},{"code":"analyse5p","label":"5 pourquoi","type":"list"},{"code":"ishikawa","label":"6M","type":"object"},{"code":"cost","label":"Coût (MAD)","type":"number"}]}',
 '{"initial":"DECLAREE",
   "states":[
     {"code":"DECLAREE","label":"Déclarée","final":false},
     {"code":"QUALIFIEE","label":"Qualifiée","final":false},
     {"code":"ANALYSE","label":"En analyse","final":false},
     {"code":"ACTIONS","label":"Actions en cours","final":false},
     {"code":"VERIFICATION","label":"Vérification d''efficacité","final":false},
     {"code":"CLOTUREE","label":"Clôturée","final":true},
     {"code":"REJETEE","label":"Rejetée","final":true}],
   "transitions":[
     {"code":"qualifier","label":"Qualifier","from":"DECLAREE","to":"QUALIFIEE","roles":["QUALITY_MANAGER","ADMIN"],"requires":["severity","ownerId"]},
     {"code":"rejeter","label":"Rejeter","from":"DECLAREE","to":"REJETEE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"lancer_analyse","label":"Lancer l''analyse","from":"QUALIFIEE","to":"ANALYSE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"valider_analyse","label":"Valider l''analyse","from":"ANALYSE","to":"ACTIONS","roles":["QUALITY_MANAGER","ADMIN"],"requires":["causeRacine"]},
     {"code":"renvoyer_analyse","label":"Renvoyer en analyse","from":"ACTIONS","to":"ANALYSE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"lancer_verification","label":"Lancer la vérification","from":"ACTIONS","to":"VERIFICATION","roles":["QUALITY_MANAGER","ADMIN"],"requires":[],"childrenIn":["FAITE","VERIFIEE"]},
     {"code":"cloturer","label":"Clôturer","from":"VERIFICATION","to":"CLOTUREE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"rouvrir_actions","label":"Rouvrir les actions","from":"VERIFICATION","to":"ACTIONS","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]}]}'),
('ACTION', 'Action', 'ACT',
 '{"fields":[{"code":"actionType","label":"Type d''action","type":"enum","values":["curative","corrective","preventive"]}]}',
 '{"initial":"A_FAIRE",
   "states":[
     {"code":"A_FAIRE","label":"À faire","final":false},
     {"code":"EN_COURS","label":"En cours","final":false},
     {"code":"FAITE","label":"Faite","final":false},
     {"code":"VERIFIEE","label":"Vérifiée","final":true}],
   "transitions":[
     {"code":"demarrer","label":"Démarrer","from":"A_FAIRE","to":"EN_COURS","roles":["FIELD_USER","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"terminer","label":"Marquer faite","from":"EN_COURS","to":"FAITE","roles":["FIELD_USER","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"reprendre","label":"Reprendre","from":"FAITE","to":"EN_COURS","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"verifier","label":"Vérifier","from":"FAITE","to":"VERIFIEE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]}]}');

-- Copie dans les tenants existants (les nouveaux tenants la reçoivent au premier usage).
INSERT INTO record_type (tenant_id, code, label, ref_prefix, field_schema, workflow)
SELECT t.id, tt.code, tt.label, tt.ref_prefix, tt.field_schema, tt.workflow
FROM tenant t CROSS JOIN record_type_template tt;
