-- M1 : notifications in-app (+ e-mail) et règles par événement x canal.

CREATE TABLE notification (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    recipient_id uuid         NOT NULL,
    type         varchar(30)  NOT NULL,
    title        varchar(300) NOT NULL,
    record_id    uuid,
    link         varchar(200),
    read         boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    -- évite de notifier deux fois le même retard / la même échéance
    dedup_key    varchar(150),
    FOREIGN KEY (tenant_id, recipient_id) REFERENCES app_user (tenant_id, id),
    FOREIGN KEY (tenant_id, record_id) REFERENCES record (tenant_id, id),
    UNIQUE (tenant_id, recipient_id, dedup_key)
);
CREATE INDEX idx_notification_recipient ON notification (tenant_id, recipient_id, read, created_at DESC);

-- Règles : absence de ligne = in-app activé, e-mail désactivé.
CREATE TABLE notification_rule (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    event_type varchar(30) NOT NULL CHECK (event_type IN ('ASSIGNED', 'TRANSITION', 'OVERDUE', 'DUE_SOON')),
    channel    varchar(10) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL')),
    enabled    boolean     NOT NULL,
    PRIMARY KEY (tenant_id, event_type, channel)
);

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE notification_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_rule
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON notification TO essor_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON notification_rule TO essor_app;
