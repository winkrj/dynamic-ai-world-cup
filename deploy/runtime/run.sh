#!/usr/bin/env bash
set -euo pipefail
umask 077
wc_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
source "$wc_script_dir/runtime-common.sh"
wc_action=${1:-check}
case "$wc_action" in check|start|stop|status) ;; *) runtime_fail 'Usage: run.sh check|start|stop|status [external-runtime.env]' ;; esac
runtime_load "${2:-/etc/worldcup/runtime.env}"
if [[ "$wc_action" == check ]]; then
    # Deliberately no network, daemon access, container changes or secret output.
    printf '%s\n' 'Runtime configuration validated; no resources changed.'
    exit 0
fi
runtime_require_host
# Never print `docker compose config`: rendered output contains credentials.
runtime_compose config --quiet
case "$wc_action" in
    start)
        install -d -m 0700 -o 70 -g 70 /var/lib/worldcup/postgres
        if [[ -f /var/lib/worldcup/postgres/pgdata/PG_VERSION ]]; then
            [[ "$(< /var/lib/worldcup/postgres/pgdata/PG_VERSION)" == 17 ]] || runtime_fail 'Existing data is not PostgreSQL 17; no automatic major upgrade.'
        fi
        runtime_ecr_login
        runtime_compose pull
        runtime_compose up -d --wait --wait-timeout 300
        ;;
    stop) runtime_compose stop ;;
    status) runtime_compose ps ;;
esac
