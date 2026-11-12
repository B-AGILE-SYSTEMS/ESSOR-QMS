-- M2 : type INCIDENT, champs terrain (clientId, occurredAt), idempotence des envois mobiles, pièces jointes.

-- Type INCIDENT (moteur générique M1 : aucune logique codée par type).
INSERT INTO record_type_template (code, label, ref_prefix, field_schema, workflow) VALUES
('INCIDENT', 'Incident', 'INC',
 '{"fields":[{"code":"categorie","label":"Catégorie","type":"enum","values":["accident_travail","presqu_accident","situation_dangereuse","environnement","materiel"]},{"code":"lieu","label":"Lieu","type":"text"},{"code":"blesses","label":"Blessés","type":"boolean"},{"code":"temoins","label":"Témoins","type":"text"}]}',
 '{"initial":"DECLARE",
   "states":[
     {"code":"DECLARE","label":"Déclaré","final":false},
     {"code":"EN_ANALYSE","label":"En analyse","final":false},
     {"code":"ACTIONS","label":"Actions en cours","final":false},
     {"code":"CLOS","label":"Clos","final":true},
     {"code":"CLASSE_SANS_SUITE","label":"Classé sans suite","final":true}],
   "transitions":[
     {"code":"analyser","label":"Analyser","from":"DECLARE","to":"EN_ANALYSE","roles":["QUALITY_MANAGER","ADMIN"],"requires":["ownerId"]},
     {"code":"classer","label":"Classer sans suite","from":"DECLARE","to":"CLASSE_SANS_SUITE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"classer_apres_analyse","label":"Classer sans suite","from":"EN_ANALYSE","to":"CLASSE_SANS_SUITE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"planifier_actions","label":"Planifier les actions","from":"EN_ANALYSE","to":"ACTIONS","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"clore","label":"Clore","from":"ACTIONS","to":"CLOS","roles":["QUALITY_MANAGER","ADMIN"],"requires":[],"childrenIn":["FAITE","VERIFIEE"]}]}');

INSERT INTO record_type (tenant_id, code, label, ref_prefix, field_schema, workflow)
SELECT t.id, tt.code, tt.label, tt.ref_prefix, tt.field_schema, tt.workflow
FROM tenant t CROSS JOIN record_type_template tt
WHERE tt.code = 'INCIDENT'
ON CONFLICT DO NOTHING;

-- Champs terrain : identifiant généré par le téléphone (dédoublonnage) et date terrain.
ALTER TABLE record ADD COLUMN client_id uuid;
ALTER TABLE record ADD COLUMN occurred_at timestamptz;
CREATE UNIQUE INDEX uq_record_client ON record (tenant_id, client_id);
CREATE INDEX idx_record_sync ON record (tenant_id, updated_at);

-- Réponses mémorisées par (tenant, Idempotency-Key), 48 h. status NULL = traitement en cours.
CREATE TABLE idempotency_key (
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    key          uuid         NOT NULL,
    user_id      uuid,
    request      varchar(300) NOT NULL,
    status       int,
    content_type varchar(100),
    body         text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key)
);
CREATE INDEX idx_idempotency_created ON idempotency_key (tenant_id, created_at);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Pièces jointes d'une fiche (fichier sur disque local, métadonnées ici). Tenant-scopé.
CREATE TABLE attachment (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    record_id    uuid         NOT NULL,
    file_name    varchar(200) NOT NULL,
    content_type varchar(100) NOT NULL,
    size         bigint       NOT NULL,
    storage_path varchar(300) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    FOREIGN KEY (tenant_id, record_id) REFERENCES record (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id)
);
CREATE INDEX idx_attachment_record ON attachment (tenant_id, record_id, created_at);

ALTER TABLE attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attachment
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_key TO essor_app;
GRANT SELECT, INSERT ON attachment TO essor_app;
