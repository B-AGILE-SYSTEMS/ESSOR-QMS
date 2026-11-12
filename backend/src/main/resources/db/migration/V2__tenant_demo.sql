-- Tenant de démonstration et son site principal. Les utilisateurs sans attribut Keycloak
-- "tenant" sont provisionnés dans ce tenant (cf. essor.tenancy.default-tenant-code).
INSERT INTO tenant (id, code, name)
VALUES ('00000000-0000-0000-0000-00000000d001', 'DEMO', 'Entreprise de démonstration');
INSERT INTO tenant_branding (tenant_id) VALUES ('00000000-0000-0000-0000-00000000d001');
INSERT INTO site (tenant_id, code, name)
VALUES ('00000000-0000-0000-0000-00000000d001', 'SIEGE', 'Siège');
