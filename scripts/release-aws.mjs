import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, lstatSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { runReleaseSmoke } from './smoke-release.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
// Account/resource identifiers stay in a local private file or GitHub environment
// variables, not this public repository. No secrets belong in this configuration.
export function validateTarget(value) {
  assert(value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === 'account,bucket,host,instance,region,repository', 'Unexpected release target fields.');
  assert(/^\d{12}$/.test(value.account) && value.region === 'ap-northeast-2'
    && /^i-[a-f0-9]{17}$/.test(value.instance), 'Invalid Seoul account/instance target.');
  assert(/^worldcup-production-imagerepository-[a-z0-9]+$/.test(value.repository)
    && /^worldcup-production-artifactbackupbucket-[a-z0-9]+$/.test(value.bucket)
    && /^[a-z0-9]+\.cloudfront\.net$/.test(value.host), 'Unexpected WorldCup repository, bucket or CloudFront host.');
  for (const field of Object.values(value)) assert(typeof field === 'string' && field.length <= 128, 'Target values must be bounded strings.');
  return Object.freeze({ ...value });
}
const runtimeFiles = ['compose.yaml', 'run.sh', 'runtime-common.sh', 'backup.sh', 'check-ready.sh',
  'nginx-start.sh', 'nginx.conf.template', 'init-worldcup.sh', 'worldcup-backup.service',
  'worldcup-backup.timer', 'rollout.sh'];
const safeEnv = { ...process.env, CANDIDATE_LIVE_TEST: 'false', CANDIDATE_INTERPRETATION_DIAGNOSTIC: 'false',
  AWS_PAGER: '', AWS_RETRY_MODE: 'standard' };

export function optionsFrom(args, env = process.env) {
  const options = { branch: 'main', quota: 'keep', awsCli: env.AWS_CLI || 'aws', config: env.AWS_RELEASE_CONFIG };
  const names = { '--revision': 'revision', '--branch': 'branch', '--quota': 'quota', '--aws-cli': 'awsCli', '--builder': 'builder', '--config': 'config' };
  const seen = new Set();
  for (let i = 0; i < args.length; i += 2) {
    const name = names[args[i]];
    assert(name && !seen.has(name) && args[i + 1], 'Use --revision <full SHA> --config /private/release.json [--branch main] [--quota keep|0|2] [--aws-cli /path/to/aws] [--builder name].');
    seen.add(name); options[name] = args[i + 1];
  }
  assert(/^[a-f0-9]{40}$/.test(options.revision ?? ''), 'A full lowercase Git commit SHA is required.');
  assert(typeof options.config === 'string' && options.config.length > 0 && !options.config.includes('\0'), 'An external release target JSON file is required.');
  assert(/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(options.branch) && !options.branch.includes('..')
    && !options.branch.endsWith('/') && !options.branch.includes('//'), 'Invalid trusted branch.');
  assert(['keep', '0', '2'].includes(options.quota), 'Quota must be keep, 0 or 2; AI budget cannot be changed here.');
  assert(typeof options.awsCli === 'string' && /^[A-Za-z0-9_./ -]+$/.test(options.awsCli)
    && !options.awsCli.startsWith('-'), 'AWS_CLI must be one executable path, not shell commands.');
  assert(!options.builder || /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(options.builder), 'Invalid Docker builder name.');
  if (env.GITHUB_ACTIONS === 'true') {
    assert(env.GITHUB_REPOSITORY === 'winkrj/dynamic-ai-world-cup'
      && env.GITHUB_REF === 'refs/heads/main' && env.GITHUB_EVENT_NAME === 'workflow_dispatch'
      && options.branch === 'main', 'GitHub deployment is restricted to manual dispatch from this repository main.');
  }
  return options;
}

// Never echo command arguments or captured output on failure: AWS login tokens and SSM output may be sensitive.
export function command(executable, args, { input, env = safeEnv, timeout = 60_000, visible = false } = {}) {
  const result = spawnSync(executable, args, { cwd: root, env, input, encoding: 'utf8', timeout,
    maxBuffer: 4 * 1024 * 1024, stdio: visible ? 'inherit' : ['pipe', 'pipe', 'pipe'] });
  assert(!result.error && result.status === 0, `${executable === 'git' ? 'Git' : 'Release'} step failed; captured output suppressed. Do not blindly retry a submitted deployment.`);
  return (result.stdout ?? '').trim();
}

export function runtimeArchive(run = command) {
  const dir = mkdtempSync(join(tmpdir(), 'worldcup-release-runtime-'));
  try {
    for (const file of runtimeFiles) assert(lstatSync(join(root, 'deploy/runtime', file)).isFile(), 'Runtime files must be regular files.');
    run('tar', ['-czf', join(dir, 'runtime.tgz'), '-C', join(root, 'deploy/runtime'), ...runtimeFiles],
      { env: { ...safeEnv, COPYFILE_DISABLE: '1' } });
    const bytes = readFileSync(join(dir, 'runtime.tgz'));
    return { base64: bytes.toString('base64'), sha256: createHash('sha256').update(bytes).digest('hex') };
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

export function rolloutCommand({ revision, image, quota, archive, target }) {
  validateTarget(target);
  const repositoryUri = `${target.account}.dkr.ecr.${target.region}.amazonaws.com/${target.repository}`;
  assert(/^[a-f0-9]{40}$/.test(revision) && image.startsWith(`${repositoryUri}@sha256:`)
    && /^[a-f0-9]{64}$/.test(image.split('@sha256:')[1] ?? ''), 'Invalid rollout identity.');
  assert(['keep', '0', '2'].includes(quota) && /^[a-f0-9]{64}$/.test(archive.sha256)
    && /^[A-Za-z0-9+/]+={0,2}$/.test(archive.base64), 'Invalid runtime archive.');
  // The private staging/archive retain 0700/0600. Only this non-secret code
  // allowlist becomes readable to the app/nginx container users after its move.
  const publicFileModes = runtimeFiles.map(file =>
    `test -f "$wc_stage/runtime/${file}" && test ! -L "$wc_stage/runtime/${file}"\nchmod ${file.endsWith('.sh') ? '0755' : '0644'} "$wc_stage/runtime/${file}"`).join('\n');
  const script = `set -eu
umask 077
wc_stage=$(mktemp -d /opt/worldcup/releases/.incoming-XXXXXXXX)
printf '%s' '${archive.base64}' | base64 --decode > "$wc_stage/runtime.tgz"
printf '%s  %s\\n' '${archive.sha256}' "$wc_stage/runtime.tgz" | sha256sum --check --status
mkdir "$wc_stage/runtime"
tar -xzf "$wc_stage/runtime.tgz" -C "$wc_stage/runtime" --no-same-owner --no-same-permissions
${publicFileModes}
chmod 0755 "$wc_stage/runtime"
bash "$wc_stage/runtime/rollout.sh" '${revision}' '${image}' '${quota}' '${target.host}' '${target.bucket}' </dev/null`;
  assert(Buffer.byteLength(script) < 23_000, 'Runtime payload exceeds the bounded SSM command size.');
  return script;
}

export async function release(options, deps = {}) {
  const run = deps.run ?? command;
  const wait = deps.wait ?? delay;
  const smoke = deps.smoke ?? runReleaseSmoke;
  const say = deps.say ?? console.log;
  const target = validateTarget(deps.target ?? JSON.parse(readFileSync(options.config, 'utf8')));
  const registry = `${target.account}.dkr.ecr.${target.region}.amazonaws.com`;
  const repositoryUri = `${registry}/${target.repository}`;
  const aws = (args, extra = {}) => run(options.awsCli, [...args, '--region', target.region, '--output', 'json'], extra);
  const pristine = () => {
    assert(run('git', ['status', '--porcelain=v1', '--untracked-files=all']) === '', 'Commit all changes before deployment.');
    assert(run('git', ['rev-parse', 'HEAD']) === options.revision, 'Checked-out revision differs from the requested release.');
    assert(['https://github.com/winkrj/dynamic-ai-world-cup.git', 'git@github.com:winkrj/dynamic-ai-world-cup.git']
      .includes(run('git', ['remote', 'get-url', 'origin'])), 'Unexpected Git origin.');
    run('git', ['check-ref-format', '--branch', options.branch]);
    const remote = run('git', ['ls-remote', '--exit-code', 'origin', `refs/heads/${options.branch}`]);
    assert(remote === `${options.revision}\trefs/heads/${options.branch}`, 'Release must equal the current trusted remote branch tip.');
  };
  pristine();
  assert(JSON.parse(aws(['sts', 'get-caller-identity'])).Account === target.account, 'Wrong AWS account; nothing was changed.');
  const instances = JSON.parse(aws(['ec2', 'describe-instances', '--instance-ids', target.instance])).Reservations?.flatMap(r => r.Instances ?? []);
  assert(instances?.length === 1 && instances[0].InstanceId === target.instance && instances[0].State?.Name === 'running'
    && instances[0].Architecture === 'x86_64' && instances[0].Tags?.some(tag => tag.Key === 'Project' && tag.Value === 'dynamic-ai-world-cup'),
  'Target must be the running x86 WorldCup instance.');
  const repositories = JSON.parse(aws(['ecr', 'describe-repositories', '--repository-names', target.repository])).repositories;
  assert(repositories?.length === 1 && repositories[0].repositoryUri === repositoryUri, 'ECR repository does not match the configured target.');
  say('Verifying the committed source; paid test flags are disabled.');
  run('bash', ['scripts/verify.sh'], { timeout: 20 * 60_000, visible: true, env: safeEnv });
  pristine();
  const archive = (deps.archive ?? runtimeArchive)(run);
  const tag = `release-${options.revision}-${(deps.nonce ?? (() => randomBytes(4).toString('hex')))()}`;
  assert(/^release-[a-f0-9]{40}-[a-f0-9]{8}$/.test(tag));
  const taggedImage = `${repositoryUri}:${tag}`;
  const authDir = (deps.authDir ?? (() => mkdtempSync(join(tmpdir(), 'worldcup-release-auth-'))))();
  const dockerEnv = { ...safeEnv, DOCKER_CONFIG: authDir };
  let submitted = false;
  let commandId;
  try {
    say('Building a revision-labelled linux/amd64 image.');
    // Build before isolated login: local buildx builders live in the user's existing Docker config.
    run('docker', ['buildx', 'build', ...(options.builder ? ['--builder', options.builder] : []),
      '--platform', 'linux/amd64', '--provenance=false', '--sbom=false', '--load',
      '--label', `org.opencontainers.image.revision=${options.revision}`, '-t', taggedImage, '.'],
    { timeout: 30 * 60_000, visible: true });
    const password = run(options.awsCli, ['ecr', 'get-login-password', '--region', target.region]);
    run('docker', ['login', '--username', 'AWS', '--password-stdin', registry], { input: `${password}\n`, env: dockerEnv });
    run('docker', ['push', taggedImage], { timeout: 20 * 60_000, env: dockerEnv });
    const details = JSON.parse(aws(['ecr', 'describe-images', '--repository-name', target.repository,
      '--image-ids', `imageTag=${tag}`])).imageDetails;
    assert(details?.length === 1 && /^sha256:[a-f0-9]{64}$/.test(details[0].imageDigest), 'Cannot resolve one immutable ECR digest.');
    const image = `${repositoryUri}@${details[0].imageDigest}`;
    pristine();
    const input = { DocumentName: 'AWS-RunShellScript', InstanceIds: [target.instance],
      Comment: `worldcup release ${options.revision}`, TimeoutSeconds: 60,
      Parameters: { commands: [rolloutCommand({ revision: options.revision, image, quota: options.quota, archive, target })], executionTimeout: ['900'] } };
    // SDK/CLI retries are disabled for SendCommand. A lost response is ambiguous, NOT permission to resubmit.
    submitted = true;
    let reply;
    try { reply = JSON.parse(aws(['ssm', 'send-command', '--cli-input-json', JSON.stringify(input)],
      { env: { ...safeEnv, AWS_MAX_ATTEMPTS: '1' } })); }
    catch { throw new Error('SSM submission response is uncertain. Inspect command history for this revision before any retry; no automatic resubmission occurred.'); }
    commandId = reply.Command?.CommandId;
    assert(/^[a-f0-9-]{36}$/.test(commandId ?? ''), 'SSM response had no command ID; inspect history before retrying.');
    say(`Deployment command: ${commandId} (revision ${options.revision}).`);
    let completed = false;
    for (let attempt = 0; attempt < 95; attempt++) {
      await wait(10_000);
      // list-command-invocations safely returns [] during SSM eventual consistency.
      const result = JSON.parse(aws(['ssm', 'list-command-invocations', '--command-id', commandId, '--details']));
      const invocations = result.CommandInvocations ?? [];
      assert(invocations.length <= 1, 'Unexpected SSM target count.');
      if (!invocations.length) continue;
      const invocation = invocations[0];
      assert(invocation.InstanceId === target.instance, 'Unexpected SSM instance.');
      if (['Pending', 'InProgress', 'Delayed'].includes(invocation.Status)) continue;
      assert(invocation.Status === 'Success', `SSM deployment ended ${invocation.Status}; inspect ${commandId}. It may have rolled back or require manual recovery.`);
      completed = true; break;
    }
    assert(completed, `SSM status remains uncertain after the bounded wait. Inspect ${commandId}; do not resubmit blindly.`);
    await smoke({ baseUrl: `https://${target.host}` });
    say(`Release verified: ${options.revision}, ${image}; HTTPS read-only smoke passed. No AI generation was requested.`);
    return { revision: options.revision, image, commandId, quota: options.quota };
  } catch (error) {
    if (submitted) say(`Deployment may have changed the host. Preserve command history${commandId ? ` ${commandId}` : ''}; never reset the DB or budget to recover.`);
    throw error;
  } finally {
    try { run('docker', ['logout', registry], { env: dockerEnv }); } catch { /* Private disposable token directory is still removed. */ }
    (deps.cleanupAuth ?? (dir => rmSync(dir, { recursive: true, force: true })))(authDir);
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { await release(optionsFrom(process.argv.slice(2))); }
  catch (error) { console.error(`Release stopped: ${error.message}`); process.exitCode = 1; }
}
