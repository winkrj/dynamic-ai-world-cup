#!/usr/bin/env bash
# Executed by SSM as root. Deploy permission is effectively root on this one host;
# IAM cannot turn AWS-RunShellScript into an image-only privilege.
set -euo pipefail
umask 077
# Keep status diagnostics outside a failing helper's private log redirection.
exec 3>&2
wc_incoming=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
wc_revision=${1:-}; wc_image=${2:-}; wc_quota=${3:-}; wc_host=${4:-}; wc_bucket=${5:-}
wc_runtime=/opt/worldcup/runtime
wc_config=/etc/worldcup/runtime.env
wc_registry=${wc_image%@sha256:*}
fail() { printf '%s\n' "$1" >&3; exit 1; }
[[ $# == 5 && "$wc_revision" =~ ^[a-f0-9]{40}$ \
    && "$wc_registry" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/worldcup-production-imagerepository-[a-z0-9]+$ \
    && "$wc_image" == "$wc_registry"@sha256:* \
    && "${wc_image##*@sha256:}" =~ ^[a-f0-9]{64}$ ]] || fail 'Invalid release identity.'
[[ "$wc_quota" == keep || "$wc_quota" == 0 || "$wc_quota" == 2 ]] || fail 'Quota must be keep, 0 or 2.'
[[ "$wc_host" =~ ^[a-z0-9]+\.cloudfront\.net$ \
    && "$wc_bucket" =~ ^worldcup-production-artifactbackupbucket-[a-z0-9]+$ ]] || fail 'Unexpected release target.'
[[ "$wc_incoming" == /opt/worldcup/releases/.incoming-*/runtime && ! -L "$wc_runtime" \
    && ! -L "$wc_config" ]] || fail 'Use a private staged runtime on the existing host.'
source "$wc_runtime/runtime-common.sh"
runtime_load "$wc_config"
runtime_require_host
[[ "$APP_IMAGE" == "$wc_registry"@sha256:* && "$PUBLIC_HOST" == "$wc_host" \
    && "$BACKUP_BUCKET" == "$wc_bucket" ]] || fail 'Host configuration does not match the release target.'
exec 8>/opt/worldcup/.deploy.lock
flock -n 8 || fail 'Another deployment is already running.'
wc_release=$(mktemp -d "/opt/worldcup/releases/release-${wc_revision:0:8}-XXXXXXXX")
wc_log="$wc_release/rollout.private.log"
wc_next_config=/etc/worldcup/.release-next.env
wc_ingress_stopped=false; wc_app_stopped=false; wc_swapped=false; wc_schema=''

db_query() {
    # No -i or heredoc: psql must not consume the remaining SSM shell program.
    docker exec --user 70:70 worldcup-postgres-1 psql --no-password --username postgres \
        --dbname worldcup --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 --command "$1" </dev/null
}
schema_fingerprint() {
    db_query "SELECT md5(COALESCE(string_agg(installed_rank::text || ':' || COALESCE(version, '') || ':' || COALESCE(checksum::text, '') || ':' || success::text, '|' ORDER BY installed_rank), '')) FROM flyway_schema_history;"
}
active_jobs() { db_query "SELECT count(*) FROM generation_job WHERE state IN ('QUEUED', 'RUNNING');"; }
load_runtime() { source "$wc_runtime/runtime-common.sh"; runtime_load "$wc_config"; }
start_app() { runtime_compose up -d --no-deps --force-recreate --wait --wait-timeout 300 app >>"$wc_log" 2>&1; }
start_ingress() { runtime_compose up -d --no-deps --force-recreate --wait --wait-timeout 60 nginx >>"$wc_log" 2>&1; }
recover() {
    local wc_exit=$1
    trap - EXIT
    [[ "$wc_exit" != 0 ]] || return 0
    set +e
    if [[ "$wc_app_stopped" == true ]]; then
        # Never restore a database or reset a cost ledger. New/failed migrations need a human-forward fix.
        runtime_compose stop nginx >>"$wc_log" 2>&1
        if [[ "$(active_jobs 2>>"$wc_log")" != 0 ]]; then
            printf 'Deployment failed with active/unknown jobs; ingress stopped, app preserved for drain. Manual recovery required. Record: %s\n' "$wc_release" >&3
            exit "$wc_exit"
        fi
        runtime_compose stop app >>"$wc_log" 2>&1
        local wc_current_schema
        wc_current_schema=$(schema_fingerprint 2>>"$wc_log")
        if [[ -z "$wc_current_schema" || "$wc_current_schema" != "$wc_schema" ]]; then
            printf 'Deployment failed; schema changed/unknown. App and ingress remain stopped; manual forward recovery required. Record: %s\n' "$wc_release" >&3
            exit "$wc_exit"
        fi
        if [[ "$wc_swapped" == true ]]; then
            mv -- "$wc_runtime" "$wc_release/runtime-failed" && mv -- "$wc_release/runtime-before" "$wc_runtime" \
                && cp -p -- "$wc_release/runtime-before.env" "$wc_config"
            if [[ $? != 0 ]]; then
                printf 'Runtime restoration failed; manual recovery required. Record: %s\n' "$wc_release" >&3
                exit "$wc_exit"
            fi
        fi
        load_runtime
        if start_app && start_ingress; then
            printf 'Deployment failed; previous runtime/image restored with unchanged schema. Record: %s\n' "$wc_release" >&3
        else
            printf 'Previous service did not become healthy; manual recovery required. Record: %s\n' "$wc_release" >&3
        fi
    elif [[ "$wc_ingress_stopped" == true ]]; then
        if start_ingress; then printf '%s\n' 'Deployment aborted before app replacement; ingress restored.' >&3
        else printf '%s\n' 'Ingress restoration failed; manual recovery required.' >&3; fi
    fi
    exit "$wc_exit"
}
trap 'recover "$?"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

[[ "$(active_jobs)" == 0 ]] || fail 'Active generation jobs exist; deployment did not start.'
cp -p -- "$wc_config" "$wc_release/runtime-before.env"
wc_schema=$(schema_fingerprint)
[[ "$wc_schema" =~ ^[a-f0-9]{32}$ ]] || fail 'Cannot establish the current migration fingerprint.'
printf '%s\n' "$wc_schema" > "$wc_release/schema-before.txt"
# Preserve all existing metadata. Only the image and an explicitly chosen quota may change.
awk -F= -v quota="$wc_quota" '$1 != "APP_IMAGE" && (quota == "keep" || $1 != "GENERATION_DAILY_LIMIT") { print }' \
    "$wc_config" > "$wc_next_config"
printf 'APP_IMAGE=%s\n' "$wc_image" >> "$wc_next_config"
if [[ "$wc_quota" != keep ]]; then printf 'GENERATION_DAILY_LIMIT=%s\n' "$wc_quota" >> "$wc_next_config"; fi
chmod 0600 "$wc_next_config"
bash "$wc_incoming/run.sh" check "$wc_next_config" >>"$wc_log" 2>&1
# Pull/validate before downtime, using the new metadata while retaining secret files on the host.
source "$wc_incoming/runtime-common.sh"
runtime_load "$wc_next_config"
runtime_ecr_login
# runtime_ecr_login installs its cleanup trap; keep both cleanup and recovery.
trap 'wc_result=$?; runtime_clear_ecr_auth || true; recover "$wc_result"' EXIT
runtime_compose pull app nginx >>"$wc_log" 2>&1
[[ "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$wc_image")" == "$wc_revision" ]] || fail 'Image revision label does not match the requested commit.'
[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$wc_image")" == linux/amd64 ]] || fail 'Expected a linux/amd64 image.'
load_runtime
# Prevent new admissions, then recheck jobs after the proxy has fully stopped.
wc_ingress_stopped=true
runtime_compose stop nginx >>"$wc_log" 2>&1
[[ "$(active_jobs)" == 0 ]] || fail 'A generation arrived before ingress drained; retry later after it finishes.'
wc_app_stopped=true
runtime_compose stop app >>"$wc_log" 2>&1
bash "$wc_runtime/backup.sh" "$wc_config" >>"$wc_log" 2>&1
[[ "$(schema_fingerprint)" == "$wc_schema" ]] || fail 'Schema changed while preparing the release.'
mv -- "$wc_runtime" "$wc_release/runtime-before"
# Set before the next move so an unexpected filesystem failure cannot silently skip recovery.
wc_swapped=true
if ! mv -- "$wc_incoming" "$wc_runtime"; then
    mv -- "$wc_release/runtime-before" "$wc_runtime"
    wc_swapped=false
    fail 'Could not install the staged runtime.'
fi
mv -- "$wc_next_config" "$wc_config"
load_runtime
start_app
[[ "$(docker inspect --format '{{.Config.Image}}' worldcup-app-1)" == "$wc_image" \
    && "$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' worldcup-app-1)" == "$wc_revision" ]] || fail 'Running image identity differs from the requested release.'
start_ingress
curl --fail --silent --show-error --proto '=https' --max-time 20 --max-filesize 65536 \
    "https://$PUBLIC_HOST/api/v1/ready" > "$wc_release/ready.public.json" 2>>"$wc_log"
grep -Eq '"service"[[:space:]]*:[[:space:]]*"dynamic-ai-world-cup"' "$wc_release/ready.public.json"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"READY"' "$wc_release/ready.public.json"
printf '%s\n' "$wc_revision" > "$wc_release/revision.txt"
printf '%s\n' "$wc_image" > "$wc_release/image.txt"
printf 'Release healthy: %s. Private backup/deployment record: %s\n' "$wc_revision" "$wc_release"
