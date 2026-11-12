#!/bin/sh
# Exécuté une seule fois, à la création du volume PostgreSQL.
# - essor_app : rôle applicatif NON propriétaire (soumis à la RLS) ; les droits sont donnés par Flyway.
# - keycloak  : base et rôle dédiés à Keycloak.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_pw="$ESSOR_DB_APP_PASSWORD" -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE essor_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD :'app_pw';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';
CREATE DATABASE keycloak OWNER keycloak;
SQL
