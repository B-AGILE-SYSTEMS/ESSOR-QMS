-- M3 : référentiels (normes = données importées), audits (type AUDIT), gestion documentaire (type DOCUMENT).

-- Types AUDIT et DOCUMENT (moteur générique M1).
-- DOCUMENT : le statut de la fiche suit celui de sa dernière version ; les transitions s'appliquent aux versions
-- (POST /api/documents/{id}/versions/{v}/transitions/{code}). Rôles vérificateur / approbateur surchargeables
-- par document (data.verifierRoles, data.approverRoles).
INSERT INTO record_type_template (code, label, ref_prefix, field_schema, workflow) VALUES
('AUDIT', 'Audit', 'AUD',
 '{"fields":[{"code":"standardIds","label":"Référentiels","type":"list"},{"code":"perimetre","label":"Périmètre","type":"text"},{"code":"auditeurs","label":"Auditeurs","type":"list"},{"code":"audites","label":"Audités","type":"text"},{"code":"datePrevue","label":"Date prévue","type":"date"}]}',
 '{"initial":"PLANIFIE",
   "states":[
     {"code":"PLANIFIE","label":"Planifié","final":false},
     {"code":"EN_COURS","label":"En cours","final":false},
     {"code":"RAPPORT","label":"Rapport","final":false},
     {"code":"CLOTURE","label":"Clôturé","final":true}],
   "transitions":[
     {"code":"demarrer","label":"Démarrer l''audit","from":"PLANIFIE","to":"EN_COURS","roles":["AUDITOR","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"rediger_rapport","label":"Passer au rapport","from":"EN_COURS","to":"RAPPORT","roles":["AUDITOR","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"reprendre","label":"Reprendre l''audit","from":"RAPPORT","to":"EN_COURS","roles":["AUDITOR","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"cloturer","label":"Clôturer","from":"RAPPORT","to":"CLOTURE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]}]}'),
('DOCUMENT', 'Document', 'DOC',
 '{"fields":[{"code":"code","label":"Code","type":"text"},{"code":"docType","label":"Type de document","type":"enum","values":["procedure","instruction","formulaire","enregistrement"]},{"code":"processus","label":"Processus","type":"text"},{"code":"recipients","label":"Destinataires","type":"object"},{"code":"verifierRoles","label":"Rôles vérificateurs","type":"list"},{"code":"approverRoles","label":"Rôles approbateurs","type":"list"},{"code":"reviewDate","label":"Date de revue","type":"date"}]}',
 '{"initial":"REDACTION",
   "states":[
     {"code":"REDACTION","label":"En rédaction","final":false},
     {"code":"VERIFICATION","label":"En vérification","final":false},
     {"code":"APPROBATION","label":"En approbation","final":false},
     {"code":"DIFFUSE","label":"Diffusé","final":false},
     {"code":"ARCHIVE","label":"Archivé","final":true}],
   "transitions":[
     {"code":"soumettre","label":"Soumettre à vérification","from":"REDACTION","to":"VERIFICATION","roles":["FIELD_USER","AUDITOR","QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"verifier","label":"Vérifier","from":"VERIFICATION","to":"APPROBATION","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"refuser_verification","label":"Renvoyer en rédaction","from":"VERIFICATION","to":"REDACTION","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]},
     {"code":"approuver","label":"Approuver et diffuser","from":"APPROBATION","to":"DIFFUSE","roles":["ADMIN"],"requires":[]},
     {"code":"refuser_approbation","label":"Renvoyer en rédaction","from":"APPROBATION","to":"REDACTION","roles":["ADMIN"],"requires":[]},
     {"code":"archiver","label":"Archiver","from":"DIFFUSE","to":"ARCHIVE","roles":["QUALITY_MANAGER","ADMIN"],"requires":[]}]}');

INSERT INTO record_type (tenant_id, code, label, ref_prefix, field_schema, workflow)
SELECT t.id, tt.code, tt.label, tt.ref_prefix, tt.field_schema, tt.workflow
FROM tenant t CROSS JOIN record_type_template tt
WHERE tt.code IN ('AUDIT', 'DOCUMENT')
ON CONFLICT DO NOTHING;

-- Référentiel (norme) du tenant : code + version (ISO45001:2018).
CREATE TABLE standard (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    code       varchar(30)  NOT NULL,
    version    varchar(20)  NOT NULL,
    title      varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code, version)
);

-- Exigences : arborescence (parent), ordre d'affichage, auditable.
CREATE TABLE requirement (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    standard_id uuid         NOT NULL,
    code        varchar(30)  NOT NULL,
    title       varchar(300) NOT NULL,
    text        text,
    parent_id   uuid,
    position    int          NOT NULL DEFAULT 0,
    auditable   boolean      NOT NULL DEFAULT true,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, standard_id, code),
    FOREIGN KEY (tenant_id, standard_id) REFERENCES standard (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES requirement (tenant_id, id)
);
CREATE INDEX idx_requirement_standard ON requirement (tenant_id, standard_id, position);

-- Lien fiche (NC, incident, document, ...) <-> exigences.
CREATE TABLE record_requirement (
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    record_id      uuid NOT NULL,
    requirement_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, record_id, requirement_id),
    FOREIGN KEY (tenant_id, record_id) REFERENCES record (tenant_id, id),
    FOREIGN KEY (tenant_id, requirement_id) REFERENCES requirement (tenant_id, id)
);
CREATE INDEX idx_record_requirement_req ON record_requirement (tenant_id, requirement_id);

-- Grille d'audit : une question par exigence auditable (ou libre), réponse et NC créée.
CREATE TABLE audit_question (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    audit_id       uuid          NOT NULL,
    requirement_id uuid,
    position       int           NOT NULL,
    text           varchar(1000) NOT NULL,
    result         varchar(20) CHECK (result IN ('conforme', 'ecart_mineur', 'ecart_majeur', 'observation', 'na')),
    comment        text,
    evidence       text,
    closed         boolean       NOT NULL DEFAULT false,
    answered_at    timestamptz,
    answered_by    uuid,
    nc_id          uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, audit_id) REFERENCES record (tenant_id, id),
    FOREIGN KEY (tenant_id, requirement_id) REFERENCES requirement (tenant_id, id),
    FOREIGN KEY (tenant_id, answered_by) REFERENCES app_user (tenant_id, id),
    FOREIGN KEY (tenant_id, nc_id) REFERENCES record (tenant_id, id)
);
CREATE INDEX idx_audit_question ON audit_question (tenant_id, audit_id, position);
CREATE UNIQUE INDEX uq_audit_question_req ON audit_question (tenant_id, audit_id, requirement_id)
    WHERE requirement_id IS NOT NULL;

-- Versions d'un document : fichier sur disque local, workflow par version, une seule en vigueur (DIFFUSE).
CREATE TABLE document_version (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    document_id  uuid         NOT NULL,
    number       int          NOT NULL,
    status       varchar(20)  NOT NULL
        CHECK (status IN ('REDACTION', 'VERIFICATION', 'APPROBATION', 'DIFFUSE', 'PERIME')),
    note         varchar(2000),
    file_name    varchar(200) NOT NULL,
    content_type varchar(100) NOT NULL,
    size         bigint       NOT NULL,
    storage_path varchar(300) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    diffused_at  timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, document_id, number),
    FOREIGN KEY (tenant_id, document_id) REFERENCES record (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id)
);
CREATE UNIQUE INDEX uq_document_in_force ON document_version (tenant_id, document_id) WHERE status = 'DIFFUSE';

-- Accusés de lecture d'une version diffusée.
CREATE TABLE document_ack (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    version_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    acked_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version_id, user_id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES document_version (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id)
);

DO $$
DECLARE tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['standard', 'requirement', 'record_requirement', 'audit_question',
                               'document_version', 'document_ack'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', tbl);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON standard, requirement, audit_question, document_version TO essor_app;
GRANT SELECT, INSERT, DELETE ON record_requirement TO essor_app;
GRANT DELETE ON audit_question TO essor_app;
GRANT SELECT, INSERT ON document_ack TO essor_app;

-- Nouveaux événements de notification : diffusion d'un document, revue périodique à J-30.
ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_event_type_check;
ALTER TABLE notification_rule ADD CONSTRAINT notification_rule_event_type_check
    CHECK (event_type IN ('ASSIGNED', 'TRANSITION', 'OVERDUE', 'DUE_SOON', 'DOCUMENT_DIFFUSED', 'REVIEW_DUE'));
