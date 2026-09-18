#!/bin/sh
set -eu
umask 077
# Validate before substitution: neither value can inject an nginx directive.
printf '%s' "${PUBLIC_HOST:-}" | grep -Eq '^[a-z0-9]+\.cloudfront\.net$' || exit 1
printf '%s' "${ORIGIN_VERIFY_TOKEN:-}" | grep -Eq '^[a-f0-9]{64}$' || exit 1
# Explicit variable list preserves nginx's own $http_* variables.
envsubst '${PUBLIC_HOST} ${ORIGIN_VERIFY_TOKEN}' < /opt/worldcup/nginx.conf.template > /tmp/nginx.conf
unset ORIGIN_VERIFY_TOKEN
# Config test errors might quote a secret-bearing line. Do not print them.
nginx -t -q -c /tmp/nginx.conf >/dev/null 2>&1 || { printf '%s\n' 'nginx configuration rejected' >&2; exit 1; }
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
