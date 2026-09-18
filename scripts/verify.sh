#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
node -e 'if (Number(process.versions.node.split(".")[0]) !== 24) { console.error("Use Node 24 LTS (.nvmrc)."); process.exit(1); }'
npm run contracts:check
npm run test:handoff
npm run build:web
cd backend
./gradlew --no-daemon test bootJar
cd "$task_root"
node scripts/check-contracts.mjs --http
