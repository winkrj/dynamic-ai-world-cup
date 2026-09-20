#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
node -e 'if (Number(process.versions.node.split(".")[0]) !== 24) { console.error("Use Node 24 LTS (.nvmrc)."); process.exit(1); }'
npm run contracts:check
npm run test:handoff
npm run test:release
node --test scripts/check-deployment-runtime.test.mjs scripts/check-aws-deployment.test.mjs scripts/check-release-pipeline.test.mjs scripts/check-github-deploy-role.test.mjs
bash -n scripts/prepare-worldcup-host.sh
npm run test:web
npm run build:web
cd backend
./gradlew --no-daemon test bootJar appJar
cd "$task_root"
node scripts/check-contracts.mjs --http
