#!/usr/bin/env bash
# Sauvegarde ESSOR QMS : base applicative + base Keycloak (pg_dump) + fichiers déposés (logos, pièces).
# Usage : ./backup.sh [répertoire_destination]   (défaut : ./backups)
# À planifier (cron) sur le serveur, puis copier les archives hors du serveur.
set -euo pipefail

cd "$(dirname "$0")"
DEST="${1:-./backups}"
KEEP_DAYS="${ESSOR_BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$DEST"
umask 077

echo "[essor] dump base essor"
docker compose exec -T postgres pg_dump -U essor_owner -d essor --format=custom > "$WORK/essor.dump"

echo "[essor] dump base keycloak"
docker compose exec -T postgres pg_dump -U essor_owner -d keycloak --format=custom > "$WORK/keycloak.dump"

echo "[essor] archive des fichiers"
docker compose exec -T backend tar -C /var/lib/essor -czf - files > "$WORK/files.tar.gz"

ARCHIVE="$DEST/essor-backup-$STAMP.tar.gz"
tar -C "$WORK" -czf "$ARCHIVE" essor.dump keycloak.dump files.tar.gz
echo "[essor] sauvegarde : $ARCHIVE"

find "$DEST" -name 'essor-backup-*.tar.gz' -mtime +"$KEEP_DAYS" -delete
