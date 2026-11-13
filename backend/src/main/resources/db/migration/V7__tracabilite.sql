-- M3b : contrôles des flux et traçabilité (module traceability).
-- Toutes les tables sont tenant-scopées, RLS FORCE, clés étrangères composites (tenant_id, id).

CREATE TABLE article (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    code       varchar(50)  NOT NULL,
    label      varchar(200) NOT NULL,
    family     varchar(100),
    unit       varchar(20)  NOT NULL DEFAULT 'u',
    tracking   varchar(10)  NOT NULL DEFAULT 'lot' CHECK (tracking IN ('lot', 'serie')),
    has_dlc    boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE partner (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    code       varchar(50)  NOT NULL,
    name       varchar(200) NOT NULL,
    type       varchar(12)  NOT NULL CHECK (type IN ('fournisseur', 'client')),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE lot (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid           NOT NULL REFERENCES tenant (id),
    article_id     uuid           NOT NULL,
    numero         varchar(80)    NOT NULL,
    supplier_id    uuid,
    supplier_lot   varchar(80),
    quantity       numeric(18, 3) NOT NULL CHECK (quantity >= 0),
    unit           varchar(20)    NOT NULL,
    dlc            date,
    site_id        uuid,
    quality_status varchar(12)    NOT NULL DEFAULT 'EN_ATTENTE'
        CHECK (quality_status IN ('EN_ATTENTE', 'LIBERE', 'QUARANTAINE', 'DEROGATION', 'REFUSE', 'DETRUIT')),
    created_by     uuid,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, article_id, numero),
    FOREIGN KEY (tenant_id, article_id) REFERENCES article (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES partner (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);
CREATE INDEX idx_lot_numero ON lot (tenant_id, numero);
CREATE INDEX idx_lot_status ON lot (tenant_id, quality_status);

CREATE TABLE lot_status_history (
    id            bigserial PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    lot_id        uuid        NOT NULL,
    from_status   varchar(12),
    to_status     varchar(12) NOT NULL,
    reason        varchar(2000),
    nc_id         uuid,
    inspection_id uuid,
    actor_id      uuid,
    at            timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lot (tenant_id, id)
);
CREATE INDEX idx_lot_history ON lot_status_history (tenant_id, lot_id, at);

-- Généalogie : source -> cible (lot) ou source -> client (shipped_to).
CREATE TABLE lot_link (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid           NOT NULL REFERENCES tenant (id),
    source_lot_id uuid           NOT NULL,
    kind          varchar(12)    NOT NULL CHECK (kind IN ('consumed_by', 'shipped_to', 'split_into')),
    target_lot_id uuid,
    partner_id    uuid,
    quantity      numeric(18, 3) NOT NULL CHECK (quantity > 0),
    reference     varchar(120),
    created_by    uuid,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, source_lot_id) REFERENCES lot (tenant_id, id),
    FOREIGN KEY (tenant_id, target_lot_id) REFERENCES lot (tenant_id, id),
    FOREIGN KEY (tenant_id, partner_id) REFERENCES partner (tenant_id, id),
    CHECK ((kind = 'shipped_to' AND partner_id IS NOT NULL AND target_lot_id IS NULL)
        OR (kind <> 'shipped_to' AND target_lot_id IS NOT NULL AND target_lot_id <> source_lot_id))
);
CREATE INDEX idx_lot_link_source ON lot_link (tenant_id, source_lot_id);
CREATE INDEX idx_lot_link_target ON lot_link (tenant_id, target_lot_id);

-- Plan de contrôle versionné : (code, version), une seule version active par code.
CREATE TABLE control_plan (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    code            varchar(50)  NOT NULL,
    version         int          NOT NULL,
    label           varchar(200) NOT NULL,
    point           varchar(20)  NOT NULL CHECK (point IN ('RECEPTION', 'STOCKAGE', 'PREPARATION',
        'PRODUCTION_ENTREE', 'PRODUCTION_ENCOURS', 'PRODUCTION_SORTIE', 'EXPEDITION')),
    target_type     varchar(12)  NOT NULL CHECK (target_type IN ('article', 'famille', 'fournisseur', 'client')),
    target_id       uuid,
    target_family   varchar(100),
    characteristics jsonb        NOT NULL,
    sampling        jsonb        NOT NULL,
    frequency       jsonb,
    active          boolean      NOT NULL DEFAULT true,
    created_by      uuid,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code, version)
);
CREATE UNIQUE INDEX idx_control_plan_active ON control_plan (tenant_id, code) WHERE active;

CREATE TABLE inspection (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    lot_id          uuid          NOT NULL,
    control_plan_id uuid          NOT NULL,
    point           varchar(20)   NOT NULL,
    site_id         uuid,
    sampling        jsonb         NOT NULL,
    sample_size     int           NOT NULL,
    decision        varchar(12)   CHECK (decision IN ('LIBERE', 'QUARANTAINE', 'DEROGATION', 'REFUSE')),
    comment         varchar(2000),
    nc_id           uuid,
    created_by      uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    decided_by      uuid,
    decided_at      timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, lot_id) REFERENCES lot (tenant_id, id),
    FOREIGN KEY (tenant_id, control_plan_id) REFERENCES control_plan (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);
CREATE INDEX idx_inspection_lot ON inspection (tenant_id, lot_id);
CREATE INDEX idx_inspection_plan ON inspection (tenant_id, control_plan_id, created_at);

-- Un résultat par (inspection, caractéristique) : auteur, date, site, version du plan, instrument.
CREATE TABLE inspection_result (
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    inspection_id  uuid        NOT NULL,
    characteristic varchar(50) NOT NULL,
    values_json    jsonb       NOT NULL,
    ok             boolean     NOT NULL,
    instrument     varchar(120),
    plan_version   int         NOT NULL,
    site_id        uuid,
    author_id      uuid,
    at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, inspection_id, characteristic),
    FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection (tenant_id, id)
);

-- Connecteur webhook sortant (un par tenant) et journal des envois.
CREATE TABLE webhook_config (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant (id),
    url        varchar(500) NOT NULL,
    secret     varchar(200) NOT NULL,
    enabled    boolean      NOT NULL DEFAULT true,
    updated_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE webhook_delivery (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    event           varchar(50)  NOT NULL,
    url             varchar(500) NOT NULL,
    payload         text         NOT NULL,
    status          varchar(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        int          NOT NULL DEFAULT 0,
    last_code       int,
    last_error      varchar(300),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_webhook_delivery ON webhook_delivery (tenant_id, created_at DESC);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['article', 'partner', 'lot', 'lot_status_history', 'lot_link', 'control_plan',
                             'inspection', 'inspection_result', 'webhook_config', 'webhook_delivery'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON article, partner, lot, control_plan, inspection, webhook_config, webhook_delivery
    TO essor_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON inspection_result TO essor_app;
GRANT SELECT, INSERT ON lot_status_history, lot_link TO essor_app;
GRANT USAGE ON SEQUENCE lot_status_history_id_seq TO essor_app;
