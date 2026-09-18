#!/usr/bin/env bash
# The release JRE image contains Bash. No credentials or paid provider calls.
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /api/v1/ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == 'HTTP/1.1 200 '* ]]
