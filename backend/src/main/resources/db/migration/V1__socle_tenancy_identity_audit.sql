-- Socle M0 : tenants (entreprise cliente), sites, utilisateurs, rattachements utilisateur -> sites,
-- habillage (branding) et journal d'audit.
-- Exécuté par le rôle propriétaire (Flyway). L'application se connecte avec essor_app,
-- rôle NON propriétaire, donc soumis à la RLS.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'essor_app') THEN
        -- LOGIN et mot de passe sont donnés par le script d'initialisation de la base (deploy/).
        CREATE ROLE essor_app NOLOGIN;
    END IF;
END
$$;

-- Contexte de transaction positionné par l'application (set_config(..., true) = SET LOCAL).
-- Contexte absent => NULL => aucune ligne visible (fermé par défaut).
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION app_current_user() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

-- Registre des tenants (racine, non tenant-scopé : nécessaire pour résoudre le tenant
-- d'un utilisateur à la connexion ; l'accès est restreint par l'application).
CREATE TABLE tenant (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code       varchar(50)  NOT NULL UNIQUE,
    name       varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

-- Habillage du tenant. Tenant-scopé.
CREATE TABLE tenant_branding (
    tenant_id         uuid PRIMARY KEY REFERENCES tenant (id),
    primary_color     varchar(7)  NOT NULL DEFAULT '#1F4E79' CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    theme             varchar(10) NOT NULL DEFAULT 'system' CHECK (theme IN ('light', 'dark', 'system')),
    logo_file         varchar(255),
    logo_content_type varchar(50),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Sites de l'entreprise cliente. Tenant-scopé.
CREATE TABLE site (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    code       varchar(50)  NOT NULL,
    name       varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, id)
);

-- Utilisateurs : un utilisateur appartient à un seul tenant. Tenant-scopé.
CREATE TABLE app_user (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    subject       varchar(255) NOT NULL UNIQUE,  -- "sub" OIDC Keycloak
    username      varchar(255) NOT NULL,
    display_name  varchar(255),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    UNIQUE (tenant_id, id)
);

-- Rattachement utilisateur -> sites (même tenant garanti par les clés composites). Tenant-scopé.
CREATE TABLE user_site (
    tenant_id  uuid NOT NULL,
    user_id    uuid NOT NULL,
    site_id    uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, site_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES site (tenant_id, id)
);

-- Journal d'audit, append-only. Tenant-scopé.
CREATE TABLE audit_log (
    id          bigserial PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id    uuid REFERENCES app_user (id),
    action      varchar(100) NOT NULL,
    entity_type varchar(100),
    entity_id   varchar(100),
    details     jsonb
);
CREATE INDEX idx_audit_log_tenant_time ON audit_log (tenant_id, occurred_at DESC);

-- RLS : même politique d'isolation sur toutes les tables tenant-scopées.
ALTER TABLE tenant_branding ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_branding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_branding
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE site ENABLE ROW LEVEL SECURITY;
ALTER TABLE site FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON site
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_user
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE user_site ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_site FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_site
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_log_select ON audit_log FOR SELECT
    USING (tenant_id = app_current_tenant());
CREATE POLICY audit_log_insert ON audit_log FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant());

-- Append-only, même pour le propriétaire.
CREATE FUNCTION audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log est en ajout seul (% interdit)', TG_OP;
END
$$;
CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();

-- Droits du rôle applicatif (moindre privilège).
GRANT USAGE ON SCHEMA public TO essor_app;
GRANT SELECT, INSERT ON tenant TO essor_app;
GRANT SELECT, INSERT, UPDATE ON tenant_branding, site, app_user TO essor_app;
GRANT SELECT, INSERT, DELETE ON user_site TO essor_app;
GRANT SELECT, INSERT ON audit_log TO essor_app;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO essor_app;
GRANT EXECUTE ON FUNCTION app_current_tenant(), app_current_user() TO essor_app;
