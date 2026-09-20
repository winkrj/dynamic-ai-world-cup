import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const template = JSON.parse(readFileSync(new URL('../deploy/aws/github-deploy-role.json', import.meta.url)));
const role = template.Resources.DeployRole.Properties;
const policies = role.Policies[0].PolicyDocument.Statement;
test('GitHub role requires an exact production subject, audience and externally approved provider', () => {
  assert.deepEqual(Object.keys(template.Resources), ['DeployRole']);
  const trust = role.AssumeRolePolicyDocument.Statement[0];
  assert.equal(trust.Action, 'sts:AssumeRoleWithWebIdentity');
  assert.deepEqual(trust.Principal, { Federated: { Ref: 'OidcProviderArn' } });
  assert.deepEqual(trust.Condition.StringEquals, {
    'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
    'token.actions.githubusercontent.com:sub': { Ref: 'GitHubSubject' },
  });
  assert.equal(trust.Condition.StringLike, undefined);
  const valid = new RegExp(`^${template.Parameters.GitHubSubject.AllowedPattern}$`);
  assert(valid.test('repo:winkrj@123/dynamic-ai-world-cup@456:environment:production'));
  for (const value of ['repo:other/project:environment:production', 'repo:winkrj/dynamic-ai-world-cup:*',
    'repo:winkrj/dynamic-ai-world-cup:pull_request', 'repo:winkrj/dynamic-ai-world-cup:environment:preview']) assert(!valid.test(value));
  assert.equal(role.MaxSessionDuration, 3600);
});
test('image publishing and remote execution target one existing repository and host', () => {
  const images = policies.find(p => p.Sid === 'OneImageRepository');
  assert.deepEqual(images.Resource, { 'Fn::Sub': 'arn:aws:ecr:ap-northeast-2:${AWS::AccountId}:repository/${RepositoryName}' });
  assert(images.Action.includes('ecr:PutImage'));
  const commands = policies.find(p => p.Sid === 'OneDeploymentHost');
  assert.equal(commands.Action, 'ssm:SendCommand');
  assert.deepEqual(commands.Resource, ['arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript',
    { 'Fn::Sub': 'arn:aws:ec2:ap-northeast-2:${AWS::AccountId}:instance/${InstanceId}' }]);
  const allActions = policies.flatMap(p => p.Action);
  assert(allActions.every(a => !a.includes('*') && !/^(iam|s3|secretsmanager):|ssm:GetParameter/.test(a)));
  for (const p of policies.filter(p => p.Resource === '*')) {
    assert(['RegistryLogin', 'DeploymentStatus', 'DeploymentTargetMetadata'].includes(p.Sid));
    assert.equal(p.Condition.StringEquals['aws:RequestedRegion'], 'ap-northeast-2');
  }
});
