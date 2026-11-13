-- Correctifs M3b : photos des contrôles, reprise des webhooks, secret webhook chiffré (AES-GCM, préfixe v1:).

CREATE TABLE inspection_attachment (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    inspection_id uuid         NOT NULL REFERENCES inspection (id),
    file_name     varchar(200) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size          bigint       NOT NULL,
    storage_path  varchar(300) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    uuid
);
CREATE INDEX idx_inspection_attachment ON inspection_attachment (tenant_id, inspection_id, created_at);
ALTER TABLE inspection_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE inspection_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inspection_attachment
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT ON inspection_attachment TO essor_app;

ALTER TABLE webhook_delivery ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX idx_webhook_delivery_retry ON webhook_delivery (tenant_id, status, next_attempt_at);

ALTER TABLE webhook_config ALTER COLUMN secret TYPE varchar(600);

ALTER TABLE inspection DROP CONSTRAINT IF EXISTS inspection_decision_check;
ALTER TABLE inspection ADD CONSTRAINT inspection_decision_check
    CHECK (decision IN ('LIBERE', 'QUARANTAINE', 'DEROGATION', 'REFUSE', 'DETRUIT'));
