# Installation on-premise d'ESSOR QMS

Un serveur Linux, Docker Engine 24+ avec le plugin Compose v2. Aucun service cloud : une fois les images
construites ou chargées, tout fonctionne en réseau fermé.

## Services

| Service    | Rôle                                                                 |
|------------|----------------------------------------------------------------------|
| `nginx`    | point d'entrée unique, port 80 : `/` → web, `/api` `/oauth2` `/login` `/logout` → back-end, `/auth` → Keycloak |
| `backend`  | API Spring Boot (port interne 8080)                                   |
| `keycloak` | fournisseur d'identité, realm `essor` importé au premier démarrage    |
| `postgres` | PostgreSQL 16 : base `essor` (application) et base `keycloak`        |
| `web`      | front Angular (profil `web`, construit depuis `../web`)               |

## Installation

```bash
cd deploy
cp .env.example .env
# Éditer .env : remplacer CHAQUE "a-changer" par un secret fort, ex. : openssl rand -base64 24
# ESSOR_PUBLIC_URL = URL par laquelle les postes atteignent le serveur (ex. http://qms.intranet)

# Avec le front (dossier ../web présent) :
docker compose --profile web up -d --build
# Sans le front :
docker compose up -d --build postgres keycloak backend nginx
```

Vérifier : `curl http://<serveur>/api/health` → `{"status":"UP"}`.

**Comptes de démo, à désactiver en production** (realm `essor`, tenant `DEMO`) : `admin/admin`, `qualite/qualite`, `terrain/terrain`.
Les désactiver ou changer leurs mots de passe avant toute mise en production (console : `http://<serveur>/auth/admin`).

## Réseau fermé

Sur une machine connectée : `docker compose --profile web build && docker compose pull`, puis
`docker save essor-qms/backend:local essor-qms/web:local postgres:16-alpine quay.io/keycloak/keycloak:26.0.7 nginx:1.27-alpine -o essor-images.tar`.
Sur le serveur : `docker load -i essor-images.tar` puis `docker compose --profile web up -d` (sans `--build`).

## Base de données et isolation des tenants

- `essor_owner` (propriétaire) : utilisé uniquement par Flyway pour les migrations.
- `essor_app` (créé par `postgres/init/01-roles.sh`) : rôle de connexion de l'application, **non propriétaire**,
  sans `BYPASSRLS`. Chaque transaction positionne `app.tenant_id` (équivalent `SET LOCAL`) et les politiques RLS
  filtrent toutes les tables tenant-scopées.
- Les scripts de `postgres/init/` ne s'exécutent qu'à la création du volume `pgdata`.

## Utilisateurs et tenants

Le tenant d'un utilisateur est donné par son attribut Keycloak `tenant` (code du tenant, ex. `DEMO`).
Sans attribut, il est rattaché au tenant `ESSOR_DEFAULT_TENANT_CODE`. Le compte applicatif est créé
automatiquement à la première connexion et rattaché à tous les sites du tenant.

## HTTPS

Placer un certificat devant Nginx (ou adapter `nginx/nginx.conf` avec `listen 443 ssl`), puis dans `.env` :
`ESSOR_PUBLIC_URL=https://...` et `ESSOR_COOKIE_SECURE=true`. Les URL de redirection Keycloak étant importées
au premier démarrage, les mettre à jour dans la console si l'URL publique change ensuite.

## Sauvegarde et restauration

```bash
./backup.sh /srv/sauvegardes        # pg_dump essor + keycloak, archive des fichiers ; rétention 14 jours
```
Exemple cron (2 h du matin) : `0 2 * * * cd /opt/essor/deploy && ./backup.sh /srv/sauvegardes`.
Copier les archives hors du serveur.

Restauration (sur une installation arrêtée sauf postgres) :
```bash
tar -xzf essor-backup-AAAAMMJJ-HHMMSS.tar.gz
docker compose exec -T postgres pg_restore -U essor_owner -d essor --clean --if-exists < essor.dump
docker compose exec -T postgres pg_restore -U essor_owner -d keycloak --clean --if-exists < keycloak.dump
docker compose run --rm -T --entrypoint sh backend -c 'tar -C /var/lib/essor -xzf -' < files.tar.gz
docker compose up -d
```

## Mise à jour

`git pull && docker compose --profile web up -d --build` : les migrations Flyway s'appliquent au démarrage du back-end.
Faire une sauvegarde avant.
