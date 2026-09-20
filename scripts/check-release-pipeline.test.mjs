import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, realpathSync, rmSync, existsSync, statSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { optionsFrom as parseOptions, rolloutCommand as makeRollout, release, validateTarget, command, runtimeArchive } from './release-aws.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const target = { account: '123456789012', region: 'ap-northeast-2', instance: 'i-0123456789abcdef0',
  repository: 'worldcup-production-imagerepository-synthetic', host: 'd123testing.cloudfront.net',
  bucket: 'worldcup-production-artifactbackupbucket-synthetic' };
const optionsFrom = (args, env = {}) => parseOptions(['--config', '/synthetic/release.json', ...args], env);
const rolloutCommand = options => makeRollout({ ...options, target });
const sha = 'a'.repeat(40);
const image = `${target.account}.dkr.ecr.${target.region}.amazonaws.com/${target.repository}@sha256:${'b'.repeat(64)}`;
const oldImage = image.replace('b'.repeat(64), 'c'.repeat(64));
const archive = { base64: Buffer.from('synthetic archive').toString('base64'), sha256: 'd'.repeat(64) };
const commandId = '11111111-2222-3333-4444-555555555555';

test('release options fail closed for unknown flags, shell fragments, budgets and untrusted CI', () => {
  const good = optionsFrom(['--revision', sha]);
  assert.equal(good.quota, 'keep');
  assert.equal(good.branch, 'main');
  assert.equal(optionsFrom(['--revision', sha, '--branch', 'feat/engine/context-feasibility', '--quota', '0']).quota, '0');
  for (const args of [['--revision', 'main'], ['--revision', sha, '--quota', '9'], ['--revision', sha, '--budget', '9'],
    ['--revision', sha, '--branch', 'main;echo bad'], ['--revision', sha, '--aws-cli', 'aws;cat key'],
    ['--revision', sha, '--quota', '0', '--quota', '2']]) assert.throws(() => optionsFrom(args));
  const ci = { GITHUB_ACTIONS: 'true', GITHUB_REPOSITORY: 'winkrj/dynamic-ai-world-cup',
    GITHUB_REF: 'refs/heads/main', GITHUB_EVENT_NAME: 'workflow_dispatch' };
  assert.equal(optionsFrom(['--revision', sha], ci).branch, 'main');
  for (const diff of [{ GITHUB_REF: 'refs/heads/feature' }, { GITHUB_EVENT_NAME: 'pull_request' }, { GITHUB_REPOSITORY: 'fork/repo' }]) {
    assert.throws(() => optionsFrom(['--revision', sha], { ...ci, ...diff }));
  }
  assert.throws(() => optionsFrom(['--revision', sha, '--branch', 'feature'], ci));
});

test('external target accepts only bounded project resource fields, not secrets or arbitrary destinations', () => {
  assert.deepEqual(validateTarget(target), target);
  for (const change of [{ account: '123' }, { region: 'us-east-1' }, { instance: 'i-12' },
    { repository: 'another-project' }, { host: 'evil.example/a' }, { bucket: 'worldcup;command' },
    { OPENAI_API_KEY: 'not-a-real-secret' }]) assert.throws(() => validateTarget({ ...target, ...change }));
  assert.throws(() => parseOptions(['--revision', sha], {}));
});

function harness(overrides = {}) {
  const calls = [];
  let polls = 0;
  const run = (exe, args, options = {}) => {
    calls.push({ exe, args, options });
    if (exe === 'git') {
      if (args[0] === 'status') return overrides.dirty ? ' M example' : '';
      if (args[0] === 'rev-parse') return sha;
      if (args[0] === 'remote') return 'https://github.com/winkrj/dynamic-ai-world-cup.git';
      if (args[0] === 'ls-remote') return `${overrides.remoteSha ?? sha}\trefs/heads/main`;
      return '';
    }
    if (args[0] === 'sts') return JSON.stringify({ Account: overrides.account ?? target.account });
    if (args[1] === 'describe-instances') return JSON.stringify({ Reservations: [{ Instances: [{
      InstanceId: target.instance, State: { Name: 'running' }, Architecture: 'x86_64',
      Tags: [{ Key: 'Project', Value: 'dynamic-ai-world-cup' }],
    }] }] });
    if (args[1] === 'describe-repositories') return JSON.stringify({ repositories: [{
      repositoryUri: `${target.account}.dkr.ecr.${target.region}.amazonaws.com/${target.repository}`,
    }] });
    if (args[1] === 'get-login-password') return 'synthetic-ECR-password';
    if (args[1] === 'describe-images') return JSON.stringify({ imageDetails: [{ imageDigest: `sha256:${'b'.repeat(64)}` }] });
    if (args[1] === 'send-command') {
      if (overrides.submitError) throw new Error('potentially sensitive provider detail');
      return JSON.stringify({ Command: { CommandId: commandId } });
    }
    if (args[1] === 'list-command-invocations') {
      polls++;
      return JSON.stringify({ CommandInvocations: polls === 1 ? [] : [{ InstanceId: target.instance, Status: overrides.status ?? 'Success' }] });
    }
    return '';
  };
  const deps = { run, target, wait: async () => {}, smoke: async () => { calls.push({ smoke: true }); },
    archive: () => archive, nonce: () => '12345678', authDir: () => '/synthetic/disposable/auth',
    cleanupAuth: () => { calls.push({ cleanup: true }); }, say: () => {} };
  return { calls, deps };
}

test('fake CLI completes verify → exact amd64 image → one SSM command → read-only smoke', async () => {
  const { calls, deps } = harness();
  const result = await release(optionsFrom(['--revision', sha, '--quota', '0', '--aws-cli', '/approved/wrapper']), deps);
  assert.equal(result.image, image);
  const verification = calls.find(call => call.exe === 'bash');
  assert.deepEqual(verification.args, ['scripts/verify.sh']);
  assert.equal(verification.options.env.CANDIDATE_LIVE_TEST, 'false');
  assert.equal(verification.options.env.CANDIDATE_INTERPRETATION_DIAGNOSTIC, 'false');
  const build = calls.find(call => call.args?.includes('buildx'));
  assert(build.args.includes('linux/amd64') && build.args.includes(`org.opencontainers.image.revision=${sha}`));
  const login = calls.find(call => call.args?.[0] === 'login');
  assert(!login.args.includes('synthetic-ECR-password'));
  assert.equal(login.options.input, 'synthetic-ECR-password\n');
  const submissions = calls.filter(call => call.args?.[1] === 'send-command');
  assert.equal(submissions.length, 1);
  assert.equal(submissions[0].exe, '/approved/wrapper');
  assert.equal(submissions[0].options.env.AWS_MAX_ATTEMPTS, '1');
  const request = JSON.parse(submissions[0].args[3]);
  assert.deepEqual(request.InstanceIds, [target.instance]);
  assert(request.Parameters.commands[0].includes(`'${image}' '0'`));
  assert(request.Parameters.commands[0].endsWith('</dev/null'));
  assert(calls.findIndex(call => call.smoke) > calls.indexOf(submissions[0]));
  assert.equal(calls.at(-1).cleanup, true);
});

test('dirty source, wrong branch revision and wrong account cannot build or submit', async () => {
  for (const overrides of [{ dirty: true }, { remoteSha: 'f'.repeat(40) }, { account: '000000000000' }]) {
    const { calls, deps } = harness(overrides);
    await assert.rejects(release(optionsFrom(['--revision', sha]), deps));
    assert(!calls.some(call => call.exe === 'docker' || call.exe === 'bash' || call.args?.[1] === 'send-command'));
  }
});

test('ambiguous SSM submission never resubmits or claims smoke success', async () => {
  const { calls, deps } = harness({ submitError: true });
  await assert.rejects(release(optionsFrom(['--revision', sha]), deps), /uncertain.*no automatic resubmission/);
  assert.equal(calls.filter(call => call.args?.[1] === 'send-command').length, 1);
  assert(!calls.some(call => call.smoke));
  assert.equal(calls.at(-1).cleanup, true);
});

test('terminal SSM failure and bounded pending wait cannot become deployment success', async () => {
  for (const status of ['Failed', 'TimedOut', 'InProgress']) {
    const { calls, deps } = harness({ status });
    await assert.rejects(release(optionsFrom(['--revision', sha]), deps));
    assert(!calls.some(call => call.smoke));
    assert(calls.filter(call => call.args?.[1] === 'list-command-invocations').length <= 95);
    assert.equal(calls.filter(call => call.args?.[1] === 'send-command').length, 1);
  }
});

test('runtime transport rejects injection, bounds payload and never downloads secrets', () => {
  const script = rolloutCommand({ revision: sha, image, quota: 'keep', archive });
  assert.match(script, /sha256sum --check --status/);
  assert.doesNotMatch(script, /get-parameter|OPENAI|PASSWORD|curl|s3/);
  assert.throws(() => rolloutCommand({ revision: sha, image: `${image};bad`, quota: 'keep', archive }));
  assert.throws(() => rolloutCommand({ revision: sha, image, quota: '0', archive: { ...archive, base64: 'a'.repeat(24000) } }));
});

test('actual checked-in runtime package fits the SSM payload and contains no secret files', () => {
  const packed = runtimeArchive();
  assert(Buffer.byteLength(rolloutCommand({ revision: sha, image, quota: 'keep', archive: packed })) < 23000);
  const members = spawnSync('tar', ['-tzf', '-'], { input: Buffer.from(packed.base64, 'base64'), encoding: 'utf8' });
  assert.equal(members.status, 0, members.stderr);
  assert(members.stdout.split('\n').includes('rollout.sh'));
  assert.doesNotMatch(members.stdout, /(?:^|\/)(?:app|postgres|proxy|runtime)\.env(?:\n|$)/);
  assert.doesNotMatch(members.stdout, /(?:^|\/)\.\./);
});

test('actual runtime extraction under umask 077 restores only public code readability, not private files', t => {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), 'worldcup-extract-test-')));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const secret = join(dir, 'app.env');
  writeFileSync(secret, 'synthetic-private-value\n', { mode: 0o600 });
  const transport = rolloutCommand({ revision: sha, image, quota: 'keep', archive: runtimeArchive() })
    .replace('/opt/worldcup/releases', dir);
  const launch = transport.lastIndexOf('\nbash "$wc_stage/runtime/rollout.sh"');
  assert(launch > 0);
  // Execute the generated transport verbatim, stopping only before the host-changing rollout.
  const extracted = spawnSync('bash', ['-c', `${transport.slice(0, launch)}\nprintf '%s' "$wc_stage"`],
    { encoding: 'utf8', timeout: 10_000 });
  assert.equal(extracted.status, 0, extracted.stderr);
  const stage = extracted.stdout;
  const mode = path => statSync(path).mode & 0o777;
  assert.equal(mode(dir), 0o700);
  assert.equal(mode(stage), 0o700);
  assert.equal(mode(join(stage, 'runtime.tgz')), 0o600);
  assert.equal(mode(join(stage, 'runtime')), 0o755);
  for (const file of readdirSync(join(stage, 'runtime'))) {
    assert.equal(mode(join(stage, 'runtime', file)), file.endsWith('.sh') ? 0o755 : 0o644, file);
  }
  // These mounted files must be readable by application UID 10001 / nginx UID 101.
  for (const file of ['check-ready.sh', 'nginx-start.sh', 'nginx.conf.template']) {
    assert((mode(join(stage, 'runtime', file)) & 0o004) !== 0, `${file} needs non-root read access`);
  }
  assert.equal(mode(secret), 0o600);
  assert.equal(readFileSync(secret, 'utf8'), 'synthetic-private-value\n');
});

test('command failures do not reveal captured output or arguments', () => {
  assert.throws(() => command(process.execPath, ['-e', 'console.error("private-test-token");process.exit(1)']), error => {
    assert(!error.message.includes('private-test-token')); return true;
  });
});

test('manual main-only environment workflow pins every action and uses the common entrypoint', () => {
  const workflow = readFileSync(join(root, '.github/workflows/release.yml'), 'utf8');
  assert.match(workflow, /workflow_dispatch:/);
  assert.doesNotMatch(workflow, /^  (push|pull_request|pull_request_target|schedule):/m);
  assert.match(workflow, /github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /ref: main/);
  assert.match(workflow, /name: production/);
  assert.match(workflow, /cancel-in-progress: false/);
  assert.match(workflow, /vars\.AWS_DEPLOY_ROLE_ARN/);
  assert.match(workflow, /id-token: write/);
  assert.doesNotMatch(workflow, /secrets\.|aws-access-key-id|aws-secret-access-key/);
  const actions = [...workflow.matchAll(/uses: ([^\n ]+)/g)].map(match => match[1]);
  assert.equal(actions.length, 4);
  for (const action of actions) assert.match(action, /^[a-zA-Z0-9-]+\/[a-zA-Z0-9-]+@[a-f0-9]{40}$/);
  assert.match(workflow, /node scripts\/release-aws\.mjs --revision "\$RELEASE_REVISION" --quota "\$RELEASE_QUOTA"/);
});

// Execute the REAL shell control flow against a disposable filesystem and fake
// Docker/AWS helpers. No daemon, cloud call, API key or real DB is used.
function hostFixture(t, scenario = 'success', quota = 'keep') {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), 'worldcup-rollout-test-')));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const host = join(dir, 'host');
  const runtime = join(host, 'opt/worldcup/runtime');
  const incoming = join(host, 'opt/worldcup/releases/.incoming-unit/runtime');
  const configDir = join(host, 'etc/worldcup');
  const bin = join(dir, 'bin');
  for (const path of [runtime, incoming, configDir, bin]) mkdirSync(path, { recursive: true });
  const metadata = `APP_IMAGE=${oldImage}\nPUBLIC_HOST=${target.host}\nBACKUP_BUCKET=${target.bucket}\nGENERATION_DAILY_LIMIT=2\nCANDIDATE_BUDGET_USD=5\n`;
  writeFileSync(join(configDir, 'runtime.env'), metadata, { mode: 0o600 });
  const common = `runtime_load() {
RUNTIME_DIR=$(cd -- "$(dirname -- "\${BASH_SOURCE[0]}")" && pwd -P)
while IFS='=' read -r key value; do export "$key=$value"; done < "$1"
}
runtime_require_host() { :; }
runtime_compose() { fake-host compose "$@"; }
runtime_ecr_login() { trap runtime_clear_ecr_auth EXIT; }
runtime_clear_ecr_auth() { fake-host auth-cleanup; }
`;
  for (const path of [runtime, incoming]) {
    writeFileSync(join(path, 'runtime-common.sh'), common);
    writeFileSync(join(path, 'backup.sh'), '#!/usr/bin/env bash\nexec fake-host backup\n');
    writeFileSync(join(path, 'run.sh'), '#!/usr/bin/env bash\nexec fake-host check\n');
  }
  const realScript = readFileSync(join(root, 'deploy/runtime/rollout.sh'), 'utf8')
    .replaceAll('/opt/worldcup', join(host, 'opt/worldcup')).replaceAll('/etc/worldcup', configDir);
  writeFileSync(join(incoming, 'rollout.sh'), realScript);
  const fake = `#!${process.execPath}
const fs=require('node:fs'),p=require('node:path');
const args=process.argv.slice(2),root=process.env.FAKE_ROOT,scenario=process.env.SCENARIO;
fs.appendFileSync(p.join(root,'calls'),JSON.stringify({args,image:process.env.APP_IMAGE})+'\\n');
const count=n=>{let f=p.join(root,n),c=fs.existsSync(f)?Number(fs.readFileSync(f,'utf8')):0;fs.writeFileSync(f,String(c+1));return c+1};
if(args[0]==='check' && scenario==='invalid-config') process.exit(1);
if(args[0]==='backup' && scenario==='backup-fail') process.exit(1);
if(args[0]==='compose' && args.includes('up') && args.at(-1)==='app' && process.env.APP_IMAGE===process.env.NEW_IMAGE){
fs.writeFileSync(p.join(root,'new-app-started'),'yes');
if(['app-fail','migration-fail'].includes(scenario)) process.exit(1);
}
if(args[0]==='docker') {
 if(args.includes('psql')) {
  const sql=args[args.indexOf('--command')+1];
  if(sql.includes('count(*)')) { const c=count('active-checks');console.log(scenario==='active-first'||(scenario==='active-race'&&c===2)?'1':'0'); }
  else console.log(scenario==='migration-fail'&&fs.existsSync(p.join(root,'new-app-started'))?'e'.repeat(32):'d'.repeat(32));
 } else if(args.includes('inspect')) console.log(args.some(a=>a.includes('Config.Image'))?process.env.NEW_IMAGE:args.some(a=>a.includes('Architecture'))?'linux/amd64':process.env.REVISION);
}
if(args[0]==='curl') {if(scenario==='smoke-fail')process.exit(1);console.log(JSON.stringify({service:'dynamic-ai-world-cup',status:'READY'}));}
`;
  writeFileSync(join(bin, 'fake-host'), fake, { mode: 0o755 });
  for (const name of ['docker', 'curl']) {
    writeFileSync(join(bin, name), `#!/usr/bin/env bash\nexec fake-host ${name} "$@"\n`, { mode: 0o755 });
  }
  // macOS lacks flock; serialize is separately asserted against the real script.
  writeFileSync(join(bin, 'flock'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
  const result = spawnSync('bash', [join(incoming, 'rollout.sh'), sha, image, quota, target.host, target.bucket], {
    encoding: 'utf8', timeout: 30_000, env: { ...process.env, PATH: `${bin}:${process.env.PATH}`,
      FAKE_ROOT: dir, SCENARIO: scenario, NEW_IMAGE: image, REVISION: sha },
  });
  assert(existsSync(join(dir, 'calls')), `Fake host was not reached: ${result.stderr}`);
  const calls = readFileSync(join(dir, 'calls'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  return { result, calls, config: readFileSync(join(configDir, 'runtime.env'), 'utf8'), metadata };
}

test('host rollout keeps quota/budget, drains ingress before second active check, backs up before replacement', t => {
  const { result, calls, config } = hostFixture(t);
  assert.equal(result.status, 0, result.stderr);
  assert.match(config, /GENERATION_DAILY_LIMIT=2/);
  assert.match(config, /CANDIDATE_BUDGET_USD=5/);
  assert(config.includes(image));
  const stopProxy = calls.findIndex(c => c.args.join(' ') === 'compose stop nginx');
  const checks = calls.flatMap((c, i) => c.args.some(a => a.includes('SELECT count(*)')) ? [i] : []);
  assert(checks[0] < stopProxy && checks[1] > stopProxy);
  const backup = calls.findIndex(c => c.args[0] === 'backup');
  const startNew = calls.findIndex(c => c.args.includes('up') && c.args.at(-1) === 'app');
  assert(backup > checks[1] && backup < startNew);
  assert.equal(calls.at(-1).args[0], 'auth-cleanup');
});

test('host quota change is explicit and preserves cumulative budget', t => {
  const { result, config } = hostFixture(t, 'success', '0');
  assert.equal(result.status, 0, result.stderr);
  assert.match(config, /GENERATION_DAILY_LIMIT=0/);
  assert.match(config, /CANDIDATE_BUDGET_USD=5/);
  assert.equal(config.match(/GENERATION_DAILY_LIMIT=/g).length, 1);
});

test('active job before drain changes nothing; race during drain restores proxy without stopping app', t => {
  for (const scenario of ['active-first', 'active-race', 'invalid-config']) {
    const { result, calls, config, metadata } = hostFixture(t, scenario);
    assert.notEqual(result.status, 0);
    assert.equal(config, metadata);
    assert(!calls.some(c => c.args.join(' ') === 'compose stop app' || c.args[0] === 'backup'));
    if (scenario === 'active-race') assert(calls.some(c => c.args.includes('up') && c.args.at(-1) === 'nginx'));
  }
});

test('backup failure and same-schema app/readiness failure restore previous metadata and application', t => {
  for (const scenario of ['backup-fail', 'app-fail', 'smoke-fail']) {
    const { result, calls, config, metadata } = hostFixture(t, scenario);
    assert.notEqual(result.status, 0);
    assert.equal(config, metadata);
    assert.match(result.stderr, /previous runtime\/image restored/);
    assert(calls.some(c => c.args.includes('up') && c.args.at(-1) === 'app' && c.image === oldImage));
  }
});

test('schema changes prevent automatic app rollback and never restore the database', t => {
  const { result, calls, config } = hostFixture(t, 'migration-fail');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /schema changed\/unknown.*manual forward recovery/);
  assert(config.includes(image));
  assert(!calls.some(c => c.args.includes('up') && c.args.at(-1) === 'app' && c.image === oldImage));
  assert(!calls.some(c => c.args.some(a => /pg_restore|DROP|TRUNCATE/.test(a))));
});

test('host shell is syntactically valid and has lock/immutable-image/secret boundaries', () => {
  const script = readFileSync(join(root, 'deploy/runtime/rollout.sh'), 'utf8');
  assert.equal(spawnSync('bash', ['-n', join(root, 'deploy/runtime/rollout.sh')]).status, 0);
  assert.match(script, /flock -n 8/);
  assert.match(script, /docker exec --user 70:70/);
  assert.doesNotMatch(script, /compose config(?!uration)|pg_restore|volume rm|down -v|OPENAI_API_KEY/);
  assert.match(script, /runtime-before\.env/);
  assert.match(script, /schema_fingerprint/);
});
