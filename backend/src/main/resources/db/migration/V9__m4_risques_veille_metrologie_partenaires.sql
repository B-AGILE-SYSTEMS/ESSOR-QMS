-- M4 : métrologie, risques et opportunités, veille réglementaire, évaluation clients et fournisseurs.
-- Toutes les tables sont tenant-scopées, RLS FORCE (boucle en fin de fichier).

-- ------------------------------------------------------------------ métrologie
CREATE TABLE instrument (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    code          varchar(50)   NOT NULL,
    label         varchar(200)  NOT NULL,
    type          varchar(80),
    site_id       uuid,
    location      varchar(200),
    serial_number varchar(100),
    range_min     numeric(18, 6),
    range_max     numeric(18, 6),
    unit          varchar(20),
    resolution    numeric(18, 6),
    emt           numeric(18, 6) NOT NULL CHECK (emt > 0),
    period_months int           NOT NULL CHECK (period_months BETWEEN 1 AND 120),
    provider      varchar(200),
    status        varchar(15)   NOT NULL DEFAULT 'en_service'
        CHECK (status IN ('en_service', 'bloque', 'en_etalonnage', 'reforme')),
    next_due      date,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);
CREATE INDEX idx_instrument_due ON instrument (tenant_id, status, next_due);

CREATE TABLE calibration (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid           NOT NULL REFERENCES tenant (id),
    instrument_id      uuid           NOT NULL,
    calibrated_on      date           NOT NULL,
    provider           varchar(200),
    internal           boolean        NOT NULL DEFAULT false,
    certificate_number varchar(100),
    points             jsonb          NOT NULL,
    emt                numeric(18, 6) NOT NULL,
    max_abs_error      numeric(18, 6) NOT NULL,
    verdict            varchar(15)    NOT NULL CHECK (verdict IN ('conforme', 'non_conforme')),
    next_due           date,
    nc_id              uuid,
    comment            varchar(2000),
    cert_file_name     varchar(200),
    cert_content_type  varchar(100),
    cert_size          bigint,
    cert_storage_path  varchar(300),
    created_by         uuid,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, instrument_id) REFERENCES instrument (tenant_id, id)
);
CREATE INDEX idx_calibration_instrument ON calibration (tenant_id, instrument_id, calibrated_on DESC);

-- Les résultats de contrôle M3b référencent l'instrument (le champ libre est conservé pour l'historique).
ALTER TABLE inspection_result ADD COLUMN instrument_id uuid;
ALTER TABLE inspection_result ADD CONSTRAINT inspection_result_instrument_fk
    FOREIGN KEY (tenant_id, instrument_id) REFERENCES instrument (tenant_id, id);
CREATE INDEX idx_inspection_result_instrument ON inspection_result (tenant_id, instrument_id, at);

-- Retards fournisseur : date de livraison attendue d'un lot reçu (facultative).
ALTER TABLE lot ADD COLUMN expected_date date;

-- ------------------------------------------------------------------ risques et opportunités
CREATE TABLE risk_scale (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    code       varchar(50)  NOT NULL,
    label      varchar(200) NOT NULL,
    dimensions jsonb        NOT NULL,
    thresholds jsonb        NOT NULL,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE risk (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    reference         varchar(30)   NOT NULL,
    type              varchar(15)   NOT NULL CHECK (type IN ('sst', 'environnement', 'qualite', 'opportunite')),
    site_id           uuid          NOT NULL,
    scale_id          uuid          NOT NULL,
    work_unit         varchar(200),
    process           varchar(200),
    hazard            varchar(500)  NOT NULL,
    situation         varchar(2000),
    existing_measures varchar(4000),
    gross             jsonb         NOT NULL,
    gross_score       int           NOT NULL,
    gross_level       varchar(20)   NOT NULL,
    residual          jsonb,
    residual_score    int,
    residual_level    varchar(20),
    reassessment_date date,
    created_by        uuid,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id),
    FOREIGN KEY (tenant_id, scale_id) REFERENCES risk_scale (tenant_id, id)
);
CREATE INDEX idx_risk_site ON risk (tenant_id, site_id, type);

CREATE TABLE risk_rating_history (
    id        bigserial PRIMARY KEY,
    tenant_id uuid        NOT NULL REFERENCES tenant (id),
    risk_id   uuid        NOT NULL,
    kind      varchar(10) NOT NULL CHECK (kind IN ('gross', 'residual')),
    ratings   jsonb       NOT NULL,
    score     int         NOT NULL,
    level     varchar(20) NOT NULL,
    at        timestamptz NOT NULL DEFAULT now(),
    actor_id  uuid,
    FOREIGN KEY (tenant_id, risk_id) REFERENCES risk (tenant_id, id)
);
CREATE INDEX idx_risk_history ON risk_rating_history (tenant_id, risk_id, at);

CREATE TABLE risk_action (
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    risk_id   uuid NOT NULL,
    action_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, risk_id, action_id),
    FOREIGN KEY (tenant_id, risk_id) REFERENCES risk (tenant_id, id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES record (tenant_id, id)
);

CREATE TABLE risk_requirement (
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    risk_id        uuid NOT NULL,
    requirement_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, risk_id, requirement_id),
    FOREIGN KEY (tenant_id, risk_id) REFERENCES risk (tenant_id, id),
    FOREIGN KEY (tenant_id, requirement_id) REFERENCES requirement (tenant_id, id)
);

-- ------------------------------------------------------------------ veille réglementaire
CREATE TABLE regulation (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    reference      varchar(200)  NOT NULL,
    title          varchar(500)  NOT NULL,
    domain         varchar(30)   NOT NULL,
    jurisdiction   varchar(30)   NOT NULL,
    published_on   date,
    effective_on   date,
    summary        text,
    source_url     varchar(500),
    status         varchar(12)   NOT NULL DEFAULT 'en_vigueur' CHECK (status IN ('en_vigueur', 'abroge', 'a_venir')),
    review_months  int           NOT NULL DEFAULT 12 CHECK (review_months BETWEEN 1 AND 60),
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference)
);

CREATE TABLE regulation_requirement (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    regulation_id uuid         NOT NULL,
    code          varchar(30)  NOT NULL,
    text          text         NOT NULL,
    position      int          NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, regulation_id, code),
    FOREIGN KEY (tenant_id, regulation_id) REFERENCES regulation (tenant_id, id)
);

CREATE TABLE regulation_applicability (
    tenant_id      uuid    NOT NULL REFERENCES tenant (id),
    requirement_id uuid    NOT NULL,
    site_id        uuid    NOT NULL,
    applicable     boolean NOT NULL,
    justification  varchar(1000),
    PRIMARY KEY (tenant_id, requirement_id, site_id),
    FOREIGN KEY (tenant_id, requirement_id) REFERENCES regulation_requirement (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);

CREATE TABLE regulation_evaluation (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    regulation_id uuid          NOT NULL,
    site_id       uuid          NOT NULL,
    result        varchar(15)   NOT NULL CHECK (result IN ('conforme', 'non_conforme', 'partiel', 'na')),
    evidence      varchar(2000),
    comment       varchar(2000),
    next_due      date          NOT NULL,
    action_id     uuid,
    evaluated_by  uuid,
    evaluated_at  timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, regulation_id) REFERENCES regulation (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);
CREATE INDEX idx_regulation_evaluation ON regulation_evaluation (tenant_id, regulation_id, site_id, evaluated_at DESC);

-- ------------------------------------------------------------------ évaluation clients et fournisseurs
CREATE TABLE evaluation_grid (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    code                varchar(50)  NOT NULL,
    label               varchar(200) NOT NULL,
    partner_type        varchar(12)  NOT NULL CHECK (partner_type IN ('fournisseur', 'client')),
    scale_max           int          NOT NULL CHECK (scale_max BETWEEN 1 AND 100),
    criteria            jsonb        NOT NULL,
    classes             jsonb        NOT NULL,
    improvement_classes text[]       NOT NULL,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE evaluation_campaign (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    grid_id      uuid        NOT NULL,
    period       varchar(10) NOT NULL,
    period_start date        NOT NULL,
    period_end   date        NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, grid_id) REFERENCES evaluation_grid (tenant_id, id)
);

CREATE TABLE partner_evaluation (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    partner_id   uuid          NOT NULL,
    grid_id      uuid          NOT NULL,
    campaign_id  uuid,
    period       varchar(10)   NOT NULL,
    period_start date          NOT NULL,
    period_end   date          NOT NULL,
    status       varchar(10)   NOT NULL CHECK (status IN ('a_faire', 'terminee')),
    scores       jsonb,
    indicators   jsonb,
    global_score numeric(6, 2),
    class        varchar(30),
    comment      varchar(2000),
    action_id    uuid,
    evaluated_by uuid,
    evaluated_at timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, partner_id, grid_id, period),
    FOREIGN KEY (tenant_id, partner_id) REFERENCES partner (tenant_id, id),
    FOREIGN KEY (tenant_id, grid_id) REFERENCES evaluation_grid (tenant_id, id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES evaluation_campaign (tenant_id, id)
);

-- ------------------------------------------------------------------ RLS, droits, notifications
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['instrument', 'calibration', 'risk_scale', 'risk', 'risk_rating_history', 'risk_action',
                             'risk_requirement', 'regulation', 'regulation_requirement', 'regulation_applicability',
                             'regulation_evaluation', 'evaluation_grid', 'evaluation_campaign',
                             'partner_evaluation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON instrument, calibration, risk_scale, risk, regulation, regulation_requirement,
    regulation_applicability, regulation_evaluation, evaluation_grid, evaluation_campaign, partner_evaluation
    TO essor_app;
GRANT SELECT, INSERT, DELETE ON risk_action, risk_requirement TO essor_app;
GRANT SELECT, INSERT ON risk_rating_history TO essor_app;
GRANT USAGE ON SEQUENCE risk_rating_history_id_seq TO essor_app;

ALTER TABLE notification_rule DROP CONSTRAINT notification_rule_event_type_check;
ALTER TABLE notification_rule ADD CONSTRAINT notification_rule_event_type_check
    CHECK (event_type IN ('ASSIGNED', 'TRANSITION', 'OVERDUE', 'DUE_SOON', 'DOCUMENT_DIFFUSED', 'REVIEW_DUE',
                          'INSTRUMENT_BLOCKED', 'CALIBRATION_DUE', 'REGULATION_DUE'));
