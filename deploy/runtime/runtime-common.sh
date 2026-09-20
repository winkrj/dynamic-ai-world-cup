#!/usr/bin/env bash
# Sourced only from checked-in scripts, never from external environment files.
runtime_fail() { printf '%s\n' "$1" >&2; exit 1; }

runtime_private_file() {
    [[ -f "$1" && ! -L "$1" ]] || runtime_fail 'A required regular configuration file is missing.'
    local wc_mode_owner
    wc_mode_owner=$(stat -c '%a:%u' "$1" 2>/dev/null || stat -f '%Lp:%u' "$1")
    [[ "$wc_mode_owner" == "600:$(id -u)" ]] || runtime_fail 'Configuration files require mode 600 and the invoking owner.'
}

runtime_read_env() {
    local wc_file="$1" wc_prefix="$2" wc_allowed="$3" wc_required_keys="${4:-$3}" wc_line wc_key wc_value wc_seen='|'
    runtime_private_file "$wc_file"
    while IFS= read -r wc_line || [[ -n "$wc_line" ]]; do
        case "$wc_line" in ''|'#'*) continue ;; esac
        [[ "$wc_line" == *=* && "$wc_line" != *$'\r'* ]] || runtime_fail 'Invalid single-line environment file.'
        wc_key=${wc_line%%=*}; wc_value=${wc_line#*=}
        case "|$wc_allowed|" in *"|$wc_key|"*) ;; *) runtime_fail 'Unexpected environment key.' ;; esac
        case "$wc_seen" in *"|$wc_key|"*) runtime_fail 'Duplicate environment key.' ;; esac
        [[ -n "$wc_value" ]] || runtime_fail 'A required configuration value is empty.'
        wc_seen="$wc_seen$wc_key|"
        # No shell evaluation, interpolation, quoting removal, or exporting secrets.
        printf -v "$wc_prefix$wc_key" '%s' "$wc_value"
    done < "$wc_file"
    local wc_required
    local IFS='|'
    for wc_required in $wc_required_keys; do
        case "$wc_seen" in *"|$wc_required|"*) ;; *) runtime_fail 'A required environment key is missing.' ;; esac
    done
}

runtime_load() {
    local wc_config="$1"
    RUNTIME_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
    SECRET_DIR=$(cd -- "$(dirname -- "$wc_config")" && pwd -P)
    export SECRET_DIR
    # Existing installations remain on staged until runtime.env explicitly opts
    # in. Never inherit these choices from the invoking shell.
    wc_cfg_CANDIDATE_ENGINE_STRATEGY=staged
    wc_cfg_CANDIDATE_FAST_TIMEOUT_SECONDS=30
    # Missing configuration preserves the original daily cap; only an explicit
    # zero disables that cap for judging. Do not inherit a caller's environment.
    wc_cfg_GENERATION_DAILY_LIMIT=2
    local wc_required_config='APP_IMAGE|NGINX_IMAGE|POSTGRES_IMAGE|PUBLIC_HOST|BACKUP_BUCKET|AWS_REGION|CANDIDATE_BUDGET_USD'
    runtime_read_env "$wc_config" wc_cfg_ "$wc_required_config|CANDIDATE_ENGINE_STRATEGY|CANDIDATE_FAST_TIMEOUT_SECONDS|GENERATION_DAILY_LIMIT" "$wc_required_config"
    [[ "$wc_cfg_APP_IMAGE" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9][a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || runtime_fail 'APP_IMAGE must be a Seoul private ECR release image digest.'
    [[ "$wc_cfg_NGINX_IMAGE" =~ ^nginx:stable-alpine@sha256:[a-f0-9]{64}$ ]] || runtime_fail 'NGINX_IMAGE must pin the official stable-alpine digest.'
    [[ "$wc_cfg_POSTGRES_IMAGE" =~ ^postgres:17-alpine@sha256:[a-f0-9]{64}$ ]] || runtime_fail 'POSTGRES_IMAGE must pin the official PostgreSQL 17 alpine digest.'
    [[ "$wc_cfg_PUBLIC_HOST" =~ ^[a-z0-9]+\.cloudfront\.net$ ]] || runtime_fail 'PUBLIC_HOST must be the deployed CloudFront distribution hostname.'
    [[ "$wc_cfg_BACKUP_BUCKET" =~ ^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]] || runtime_fail 'Invalid private backup bucket name.'
    [[ "$wc_cfg_AWS_REGION" == ap-northeast-2 ]] || runtime_fail 'This deployment is scoped to the Seoul region.'
    [[ "$wc_cfg_CANDIDATE_BUDGET_USD" == 0 || "$wc_cfg_CANDIDATE_BUDGET_USD" == 5 ]] || runtime_fail 'Only cumulative AI budgets 0 or approved 5 are allowed.'
    [[ "$wc_cfg_CANDIDATE_ENGINE_STRATEGY" == catalog || "$wc_cfg_CANDIDATE_ENGINE_STRATEGY" == staged ]] || runtime_fail 'Only catalog or staged candidate strategies are allowed.'
    [[ "$wc_cfg_CANDIDATE_FAST_TIMEOUT_SECONDS" == 30 ]] || runtime_fail 'The production fast candidate timeout must be 30 seconds.'
    [[ "$wc_cfg_GENERATION_DAILY_LIMIT" == 0 || "$wc_cfg_GENERATION_DAILY_LIMIT" == 2 ]] || runtime_fail 'Daily generation limit must be 0 (judging) or 2 (normal).'
    local wc_name wc_val
    for wc_name in APP_IMAGE NGINX_IMAGE POSTGRES_IMAGE PUBLIC_HOST BACKUP_BUCKET AWS_REGION CANDIDATE_BUDGET_USD CANDIDATE_ENGINE_STRATEGY CANDIDATE_FAST_TIMEOUT_SECONDS GENERATION_DAILY_LIMIT; do
        wc_val="wc_cfg_$wc_name"; wc_val=${!wc_val}
        case "$wc_val" in *REPLACE*|*replace*|*placeholder*|*example*|*@sha256:0000000000000000000000000000000000000000000000000000000000000000)
            runtime_fail 'A deployment placeholder is not a usable configuration.' ;;
        esac
        export "$wc_name=$wc_val"
    done
    runtime_read_env "$SECRET_DIR/app.env" wc_app_ 'DATABASE_PASSWORD|OPENAI_API_KEY'
    runtime_read_env "$SECRET_DIR/postgres.env" wc_db_ 'POSTGRES_PASSWORD|WORLDCUP_PASSWORD'
    runtime_read_env "$SECRET_DIR/proxy.env" wc_proxy_ 'ORIGIN_VERIFY_TOKEN'
    [[ "$wc_app_DATABASE_PASSWORD" =~ ^[a-f0-9]{64}$ && "$wc_db_POSTGRES_PASSWORD" =~ ^[a-f0-9]{64}$
        && "$wc_proxy_ORIGIN_VERIFY_TOKEN" =~ ^[a-f0-9]{64}$ ]] || runtime_fail 'Database passwords and origin token require 64 random hex characters.'
    [[ "$wc_app_DATABASE_PASSWORD" == "$wc_db_WORLDCUP_PASSWORD" ]] || runtime_fail 'The application database passwords do not match.'
    [[ "$wc_db_POSTGRES_PASSWORD" != "$wc_app_DATABASE_PASSWORD" && "$wc_proxy_ORIGIN_VERIFY_TOKEN" != "$wc_app_DATABASE_PASSWORD"
        && "$wc_proxy_ORIGIN_VERIFY_TOKEN" != "$wc_db_POSTGRES_PASSWORD" ]] || runtime_fail 'Admin, application, and origin secrets must differ.'
    [[ "$wc_app_OPENAI_API_KEY" =~ ^sk-[A-Za-z0-9_.-]+$ && ${#wc_app_OPENAI_API_KEY} -ge 23
        && ${#wc_app_OPENAI_API_KEY} -le 515 ]] || runtime_fail 'A server API key is required, not a placeholder.'
    case "$wc_app_OPENAI_API_KEY" in *REPLACE*|*replace*|*placeholder*|*example*) runtime_fail 'A server API key placeholder is not allowed.' ;; esac
    unset wc_app_DATABASE_PASSWORD wc_app_OPENAI_API_KEY wc_db_POSTGRES_PASSWORD wc_db_WORLDCUP_PASSWORD wc_proxy_ORIGIN_VERIFY_TOKEN
    # Avoid implicit .env loading and unrelated caller Compose overrides.
    unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES
}

runtime_compose() {
    docker compose --project-name worldcup --project-directory "$RUNTIME_DIR" --env-file /dev/null -f "$RUNTIME_DIR/compose.yaml" "$@"
}

runtime_clear_ecr_auth() {
    # Only this invocation's known token file, never an existing Docker config.
    rm -f -- "$wc_ecr_config_dir/config.json"
    rmdir -- "$wc_ecr_config_dir" 2>/dev/null || true
}

runtime_ecr_login() {
    wc_ecr_config_dir=$(mktemp -d /tmp/worldcup-docker-XXXXXXXX)
    chmod 0700 "$wc_ecr_config_dir"
    export DOCKER_CONFIG="$wc_ecr_config_dir"
    trap runtime_clear_ecr_auth EXIT
    local wc_registry=${APP_IMAGE%%/*}
    # Host instance-role credentials; never store the token in an argument,
    # variable or environment. Suppress tool output that could include secrets.
    if ! aws ecr get-login-password --region "$AWS_REGION" 2>/dev/null \
        | docker login --username AWS --password-stdin "$wc_registry" >/dev/null 2>&1; then
        runtime_fail 'ECR authentication failed; image pull was not started.'
    fi
    chmod 0600 "$wc_ecr_config_dir/config.json"
}

runtime_require_host() {
    [[ "$(id -u)" == 0 ]] || runtime_fail 'Runtime changes require the deployment host root account.'
    mountpoint -q /var/lib/worldcup || runtime_fail 'The retained data volume must be mounted at /var/lib/worldcup first.'
    [[ ! -L /var/lib/worldcup/postgres && ! -L /var/lib/worldcup/backups ]] || runtime_fail 'Data directories must not be symbolic links.'
}
