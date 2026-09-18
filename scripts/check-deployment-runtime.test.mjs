import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, chmodSync, rmSync, mkdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../', import.meta.url));
const runtime = join(root, 'deploy/runtime');
const source = name => readFileSync(join(runtime, name), 'utf8');
const digest = 'a'.repeat(64);
// Synthetic credentials only. Never read a real local/production environment file.
const metadata = {
  APP_IMAGE: `123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/worldcup@sha256:${digest}`,
  NGINX_IMAGE: `nginx:stable-alpine@sha256:${digest}`,
  POSTGRES_IMAGE: `postgres:17-alpine@sha256:${digest}`,
  PUBLIC_HOST: 'd123testing.cloudfront.net',
  BACKUP_BUCKET: 'worldcup-unit-test-backups',
  AWS_REGION: 'ap-northeast-2',
  CANDIDATE_BUDGET_USD: '5',
};
const secrets = {
  'app.env': `DATABASE_PASSWORD=${'b'.repeat(64)}\nOPENAI_API_KEY=sk-unit-test-not-a-real-api-key-123456789\n`,
  'postgres.env': `POSTGRES_PASSWORD=${'c'.repeat(64)}\nWORLDCUP_PASSWORD=${'b'.repeat(64)}\n`,
  'proxy.env': `ORIGIN_VERIFY_TOKEN=${'d'.repeat(64)}\n`,
};
function fixture(t, overrides = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'worldcup-runtime-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const config = Object.entries({ ...metadata, ...overrides }).map(([key, value]) => `${key}=${value}`).join('\n') + '\n';
  for (const [name, value] of Object.entries({ 'runtime.env': config, ...secrets })) {
    writeFileSync(join(dir, name), value, { mode: 0o600 });
  }
  return dir;
}
function check(dir) {
  return spawnSync('bash', [join(runtime, 'run.sh'), 'check', join(dir, 'runtime.env')], {
    cwd: root, env: { ...process.env }, encoding: 'utf8', timeout: 10_000,
  });
}
function reject(dir, expected) {
  const result = check(dir);
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, expected);
  assert.equal(result.stdout, '');
  for (const value of ['b'.repeat(64), 'c'.repeat(64), 'd'.repeat(64), 'sk-unit-test']) {
    assert(!result.stderr.includes(value), 'Failure messages must not reveal credentials.');
  }
}

test('validates external private configuration without executing Docker or paid calls', t => {
  const dir = fixture(t);
  const result = check(dir);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'Runtime configuration validated; no resources changed.\n');
  assert.equal(result.stderr, '');
});

test('permits zero AI budget for recovery, not any unapproved increase', t => {
  const stopped = fixture(t, { CANDIDATE_BUDGET_USD: '0' });
  assert.equal(check(stopped).status, 0);
  for (const amount of ['6', '-1', '5.01', '500', '5e0', '']) {
    reject(fixture(t, { CANDIDATE_BUDGET_USD: amount }), /budgets|empty/);
  }
});

test('rejects mutable/unofficial images, placeholders and a different region', t => {
  for (const override of [
    { APP_IMAGE: 'worldcup:latest' }, { APP_IMAGE: `worldcup@sha256:${'0'.repeat(64)}` },
    { NGINX_IMAGE: `attacker/nginx@sha256:${digest}` },
    { POSTGRES_IMAGE: `postgres:18-alpine@sha256:${digest}` },
    { BACKUP_BUCKET: 'replace-with-bucket' }, { AWS_REGION: 'us-east-1' },
  ]) reject(fixture(t, override), /digest|placeholder|region/);
});

test('only a Seoul private ECR registry can receive deployment authentication', t => {
  for (const repository of ['worldcup', 'public.ecr.aws/worldcup',
    '123456789012.dkr.ecr.us-east-1.amazonaws.com/worldcup',
    '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com.evil.invalid/worldcup',
    '12345678901.dkr.ecr.ap-northeast-2.amazonaws.com/worldcup',
    'https://123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/worldcup']) {
    reject(fixture(t, { APP_IMAGE: `${repository}@sha256:${digest}` }), /Seoul private ECR/);
  }
});

test('rejects public host/header injection and malformed backup targets', t => {
  for (const host of ['localhost', 'https://d123.cloudfront.net', 'd123.cloudfront.net/path',
    'd123.cloudfront.net;return 200;', 'd123.cloudfront.net\nEXTRA=1']) {
    reject(fixture(t, { PUBLIC_HOST: host }), /hostname|Unexpected/);
  }
  for (const bucket of ['s3://bucket', 'bucket/path', '../bucket', 'bucket;touch']) {
    reject(fixture(t, { BACKUP_BUCKET: bucket }), /bucket/);
  }
});

test('never sources shell code from environment files', t => {
  const dir = fixture(t);
  writeFileSync(join(dir, 'app.env'), `${secrets['app.env']}export SHELL_OVERRIDE=1\n`);
  reject(dir, /Unexpected/);
  writeFileSync(join(dir, 'app.env'), `DATABASE_PASSWORD=$(printf dangerous)\nOPENAI_API_KEY=sk-unit-test-not-a-real-api-key-123456789\n`);
  reject(dir, /64 random hex/);
});

test('rejects unknown, duplicate and missing secrets plus permissive file modes', t => {
  for (const [name, content] of [
    ['app.env', `${secrets['app.env']}POSTGRES_PASSWORD=forbidden\n`],
    ['proxy.env', `${secrets['proxy.env']}ORIGIN_VERIFY_TOKEN=${'e'.repeat(64)}\n`],
    ['postgres.env', `POSTGRES_PASSWORD=${'c'.repeat(64)}\n`],
  ]) {
    const dir = fixture(t); writeFileSync(join(dir, name), content);
    reject(dir, /Unexpected|Duplicate|required/);
  }
  const dir = fixture(t); chmodSync(join(dir, 'app.env'), 0o644);
  reject(dir, /mode 600/);
});

test('keeps database, admin and proxy secrets distinct and app DB passwords aligned', t => {
  for (const body of [
    `POSTGRES_PASSWORD=${'c'.repeat(64)}\nWORLDCUP_PASSWORD=${'e'.repeat(64)}\n`,
    `POSTGRES_PASSWORD=${'b'.repeat(64)}\nWORLDCUP_PASSWORD=${'b'.repeat(64)}\n`,
  ]) {
    const dir = fixture(t); writeFileSync(join(dir, 'postgres.env'), body);
    reject(dir, /match|differ/);
  }
});

test('Docker Compose resolves only synthetic credentials into their own containers', t => {
  const dir = fixture(t);
  const result = spawnSync('docker', ['compose', '--project-name', 'worldcup', '--project-directory', runtime,
    '--env-file', '/dev/null', '-f', join(runtime, 'compose.yaml'), 'config', '--format', 'json'], {
    cwd: root, env: { ...process.env, ...metadata, SECRET_DIR: dir }, encoding: 'utf8', timeout: 20_000,
  });
  assert.equal(result.status, 0, `Docker Compose >=2.30 config check required: ${result.stderr}`);
  const config = JSON.parse(result.stdout);
  assert.deepEqual(Object.keys(config.services).sort(), ['app', 'nginx', 'postgres']);
  const { app, nginx, postgres } = config.services;
  assert.equal(app.environment.DATABASE_PASSWORD, 'b'.repeat(64));
  assert.equal(app.environment.SPRING_PROFILES_ACTIVE, 'prod,live,proxy');
  assert.equal(app.environment.TRUSTED_PROXY_ADDRESS, '172.30.52.2');
  assert.equal(app.environment.CANDIDATE_BUDGET_USD, '5');
  assert.equal(app.environment.GENERATION_DAILY_LIMIT, '2');
  assert.match(app.environment.JAVA_TOOL_OPTIONS, /-Xmx384m/);
  for (const key of ['POSTGRES_PASSWORD', 'ORIGIN_VERIFY_TOKEN', 'WORLDCUP_PASSWORD']) assert.equal(app.environment[key], undefined);
  for (const service of [nginx, postgres]) assert.equal(service.environment.OPENAI_API_KEY, undefined);
  assert.equal(nginx.environment.POSTGRES_PASSWORD, undefined);
  assert.equal(postgres.environment.ORIGIN_VERIFY_TOKEN, undefined);
  assert.equal(app.ports, undefined); assert.equal(postgres.ports, undefined);
  assert.equal(nginx.ports.length, 1);
  assert.equal(String(nginx.ports[0].published), '80'); assert.equal(nginx.ports[0].target, 8080);
  assert.equal(Number(app.mem_limit), 768 * 1024 ** 2);
  assert.equal(Number(postgres.mem_limit), 384 * 1024 ** 2);
  assert.equal(Number(nginx.mem_limit), 64 * 1024 ** 2);
  assert.equal(config.networks.database.internal, true);
  assert.equal(config.networks.edge.ipam.config[0].subnet, '172.30.52.0/24');
  assert.equal(config.networks.database.ipam.config[0].subnet, '172.30.53.0/24');
  assert.deepEqual(Object.keys(nginx.networks), ['edge']);
  assert.deepEqual(Object.keys(postgres.networks), ['database']);
  assert.equal(nginx.networks.edge.ipv4_address, '172.30.52.2');
  assert.equal(app.networks.edge.ipv4_address, '172.30.52.3');
  assert.equal(app.networks.database.ipv4_address, '172.30.53.3');
  assert.equal(postgres.networks.database.ipv4_address, '172.30.53.2');
  for (const [name, service] of Object.entries(config.services)) {
    assert.equal(service.platform, 'linux/amd64');
    assert.equal(service.read_only, true);
    assert.deepEqual(service.cap_drop, ['ALL']);
    assert.deepEqual(service.security_opt, ['no-new-privileges:true']);
    assert.notEqual(service.user.split(':')[0], '0');
    assert(service.tmpfs.some(path => path.startsWith('/tmp:')));
    assert(service.pids_limit > 0, name);
    // Compose's canonical JSON omits false (omitempty); require the explicit
    // setting in the source too so a future short-syntax mount cannot create it.
    assert(service.volumes.every(volume => volume.type === 'bind' && volume.bind?.create_host_path !== true));
    assert.equal(service.logging.options['max-size'], '10m');
  }
  assert(postgres.volumes.some(volume => volume.source === '/var/lib/worldcup/postgres' && !volume.read_only));
  assert.equal((source('compose.yaml').match(/create_host_path: false/g) ?? []).length,
    Object.values(config.services).reduce((sum, service) => sum + service.volumes.length, 0));
  assert.equal(app.depends_on.postgres.condition, 'service_healthy');
  assert.equal(nginx.depends_on.app.condition, 'service_healthy');
  assert.match(postgres.healthcheck.test.join(' '), /-h 127\.0\.0\.1/);
});

test('nginx preserves the CloudFront rightmost viewer IP and removes spoofable headers/secrets', () => {
  const config = source('nginx.conf.template');
  assert.match(config, /if \(\$http_x_origin_verify != "\$\{ORIGIN_VERIFY_TOKEN\}"\) \{ return 403; \}/);
  assert.match(config, /if \(\$http_x_forwarded_for = ""\) \{ return 403; \}/);
  assert.match(config, /proxy_set_header X-Forwarded-For \$http_x_forwarded_for;/);
  assert(!config.includes('$proxy_add_x_forwarded_for'));
  assert(!config.includes('set_real_ip_from'));
  assert.match(config, /proxy_set_header X-Forwarded-Proto https;/);
  assert.match(config, /proxy_set_header Host "\$\{PUBLIC_HOST\}";/);
  for (const header of ['Forwarded', 'X-Forwarded-Host', 'X-Forwarded-Port', 'X-Real-IP', 'X-Origin-Verify']) {
    assert(config.includes(`proxy_set_header ${header} "";`));
  }
  assert.match(config, /access_log off;/);
  assert.match(config, /proxy_intercept_errors off;/);
  assert.match(source('nginx-start.sh'), /envsubst '\$\{PUBLIC_HOST\} \$\{ORIGIN_VERIFY_TOKEN\}'/);
  assert.match(source('nginx-start.sh'), /unset ORIGIN_VERIFY_TOKEN/);
});

test('initialization creates a non-superuser database owner without a password in argv', () => {
  const init = source('init-worldcup.sh');
  assert.match(init, /\\getenv app_password WORLDCUP_PASSWORD/);
  assert.match(init, /NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password'/);
  assert.match(init, /CREATE DATABASE worldcup OWNER worldcup/);
  assert.match(init, /REVOKE CREATE ON SCHEMA public FROM PUBLIC/);
  assert(!init.includes('--password='));
  assert(!init.includes('ALTER ROLE'));
  assert(!init.includes('DROP '));
});

test('database-only backups are private, validated then atomically finalized and uploaded', () => {
  const backup = source('backup.sh');
  assert.match(backup, /umask 077/);
  assert.match(backup, /flock -n 9/);
  assert.match(backup, /mktemp \/var\/lib\/worldcup\/backups\/\.incomplete-/);
  assert.match(backup, /pg_dump --no-password --username postgres --dbname worldcup --format=custom/);
  assert(backup.indexOf('pg_restore --list') < backup.indexOf('mv --'));
  assert(backup.indexOf('mv --') < backup.indexOf('aws s3 cp'));
  assert.match(backup, /chmod 0600/);
  assert.match(backup, /s3:\/\/\$BACKUP_BUCKET\/backups\/\$wc_name/);
  assert.match(backup, /--sse AES256/);
  assert(!backup.includes('pg_dumpall'));
  assert(!backup.includes('--recursive'));
  assert(!backup.includes('aws configure'));
  assert(!backup.includes('rm '));
  assert.match(source('worldcup-backup.timer'), /OnCalendar=\*-\*-\* 18:00:00 UTC/);
  assert.match(source('worldcup-backup.service'), /UMask=0077/);
});

test('runtime refuses an unmounted retained volume, never deletes or restores data', () => {
  const common = source('runtime-common.sh');
  const run = source('run.sh');
  assert.match(common, /mountpoint -q \/var\/lib\/worldcup/);
  assert.match(run, /runtime_require_host/);
  assert.match(run, /PG_VERSION/);
  assert.match(run, /config --quiet/);
  assert.match(run, /up -d --wait --wait-timeout 300/);
  assert(!run.includes('down'));
  assert(!run.includes('rm '));
  assert(!run.includes('pg_restore'));
  assert.match(source('check-ready.sh'), /GET \/api\/v1\/ready HTTP\/1\.1/);
});

test('ECR tokens use a pipeline into isolated Docker config before every pull', () => {
  const common = source('runtime-common.sh');
  const run = source('run.sh');
  assert.match(common, /mktemp -d \/tmp\/worldcup-docker-XXXXXXXX/);
  assert.match(common, /chmod 0700 "\$wc_ecr_config_dir"/);
  assert.match(common, /export DOCKER_CONFIG="\$wc_ecr_config_dir"/);
  assert.match(common, /trap runtime_clear_ecr_auth EXIT/);
  assert.match(common, /aws ecr get-login-password --region "\$AWS_REGION" 2>\/dev\/null \\\n\s*\| docker login --username AWS --password-stdin "\$wc_registry" >\/dev\/null 2>&1/);
  assert.match(common, /rm -f -- "\$wc_ecr_config_dir\/config\.json"/);
  assert(!common.includes('rm -rf'));
  assert(!common.includes('$(aws ecr get-login-password'));
  assert(run.indexOf('runtime_ecr_login') < run.indexOf('runtime_compose pull'));
  assert(run.indexOf('runtime_compose pull') < run.indexOf('runtime_compose up'));
});

test('ECR auth succeeds or fails without real AWS calls, leaked tokens or changing original Docker config', t => {
  for (const fails of [false, true]) {
    const dir = fixture(t);
    const bin = join(dir, 'bin'); mkdirSync(bin);
    const original = join(dir, 'original-docker'); mkdirSync(original, { mode: 0o700 });
    const originalContent = '{"auths":{"existing.invalid":{"auth":"synthetic-existing-auth"}}}\n';
    writeFileSync(join(original, 'config.json'), originalContent, { mode: 0o600 });
    // All three external commands used below are shell fixtures; no SDK, Docker
    // daemon or network is invoked. This token is deliberately not a credential.
    writeFileSync(join(bin, 'aws'), `#!/bin/bash
set -eu
[[ "$*" == 'ecr get-login-password --region ap-northeast-2' ]]
if [[ "$WC_TEST_FAILURE" == 1 ]]; then printf '%s\\n' 'synthetic-sensitive-error' >&2; exit 17; fi
printf '%s\\n' 'synthetic-ecr-stdin-token'
`, { mode: 0o755 });
    writeFileSync(join(bin, 'docker'), `#!/bin/bash
set -eu
[[ "$*" == 'login --username AWS --password-stdin 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com' ]]
printf '%s\\n' "$DOCKER_CONFIG" > "$WC_TEST_TRACE/config-dir"
wc_mode=$(stat -c '%a' "$DOCKER_CONFIG" 2>/dev/null || stat -f '%Lp' "$DOCKER_CONFIG")
[[ "$wc_mode" == 700 ]]
IFS= read -r wc_input
[[ "$wc_input" == synthetic-ecr-stdin-token ]]
printf '%s\\n' '{"auths":{"synthetic.invalid":{"auth":"synthetic-only"}}}' > "$DOCKER_CONFIG/config.json"
printf '%s\\n' 'synthetic-sensitive-output'
printf '%s\\n' 'synthetic-sensitive-error' >&2
`, { mode: 0o755 });
    const result = spawnSync('bash', ['-c',
      'set -euo pipefail; umask 077; source "$1"; runtime_ecr_login; printf "Authentication completed.\\n"',
      'runtime-auth-test', join(runtime, 'runtime-common.sh')], {
      cwd: root, env: { ...process.env, APP_IMAGE: metadata.APP_IMAGE, AWS_REGION: metadata.AWS_REGION,
        DOCKER_CONFIG: original, PATH: `${bin}:${process.env.PATH}`, WC_TEST_TRACE: dir, WC_TEST_FAILURE: fails ? '1' : '0' },
      encoding: 'utf8', timeout: 10_000,
    });
    assert.equal(result.status, fails ? 1 : 0, result.stderr);
    assert.equal(result.stdout, fails ? '' : 'Authentication completed.\n');
    assert.equal(result.stderr, fails ? 'ECR authentication failed; image pull was not started.\n' : '');
    const created = readFileSync(join(dir, 'config-dir'), 'utf8').trim();
    assert.match(created, /^\/tmp\/worldcup-docker-[A-Za-z0-9]+$/);
    assert(!existsSync(created), 'The temporary token file and directory must be removed on exit.');
    assert.equal(readFileSync(join(original, 'config.json'), 'utf8'), originalContent);
  }
});

test('all shell entrypoints parse without running them', () => {
  for (const name of ['runtime-common.sh', 'run.sh', 'backup.sh', 'check-ready.sh', 'nginx-start.sh', 'init-worldcup.sh']) {
    const result = spawnSync('bash', ['-n', resolve(runtime, name)], { encoding: 'utf8' });
    assert.equal(result.status, 0, `${name}: ${result.stderr}`);
  }
});
