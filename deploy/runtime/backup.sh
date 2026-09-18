#!/usr/bin/env bash
set -euo pipefail
umask 077
wc_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
source "$wc_script_dir/runtime-common.sh"
runtime_load "${1:-/etc/worldcup/runtime.env}"
runtime_require_host
install -d -m 0700 -o 0 -g 0 /var/lib/worldcup/backups
exec 9>/var/lib/worldcup/backups/.backup.lock
flock -n 9 || runtime_fail 'Another backup is already running.'
wc_partial=$(mktemp /var/lib/worldcup/backups/.incomplete-XXXXXXXX)
# On failure keep the private incomplete file for diagnosis, never upload it.
runtime_compose exec -T --user 70:70 postgres pg_dump --no-password --username postgres --dbname worldcup --format=custom > "$wc_partial"
[[ -s "$wc_partial" ]] || runtime_fail 'Database dump is empty; upload refused.'
runtime_compose exec -T --user 70:70 postgres pg_restore --list < "$wc_partial" >/dev/null
wc_name="worldcup-$(date -u +%Y%m%dT%H%M%SZ)-${wc_partial##*-}.dump"
wc_completed="/var/lib/worldcup/backups/$wc_name"
mv -- "$wc_partial" "$wc_completed"
chmod 0600 "$wc_completed"
# Instance-role credentials only; no access keys or env files are uploaded.
aws s3 cp "$wc_completed" "s3://$BACKUP_BUCKET/backups/$wc_name" --region "$AWS_REGION" --sse AES256 --only-show-errors --no-progress
printf 'Database backup completed and uploaded: %s\n' "$wc_name"
