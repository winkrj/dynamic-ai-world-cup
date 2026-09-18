import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const foundation = JSON.parse(readFileSync(new URL('../deploy/aws/foundation.json', import.meta.url)));
const edge = JSON.parse(readFileSync(new URL('../deploy/aws/edge.json', import.meta.url)));
const resources = foundation.Resources;
const props = (name) => resources[name].Properties;
const ref = (name) => ({ Ref: name });
const att = (name, field) => ({ 'Fn::GetAtt': [name, field] });
const sub = (value) => ({ 'Fn::Sub': value });
const policy = props('InstanceRole').Policies[0].PolicyDocument.Statement;
const statement = (sid) => policy.find((entry) => entry.Sid === sid);
const distribution = edge.Resources.Distribution.Properties.DistributionConfig;

test('foundation consumes existing network and pinned AMI without inventing account defaults', () => {
  assert.deepEqual(Object.keys(foundation.Parameters).sort(), [
    'AmiId', 'CloudFrontPrefixListId', 'CloudFrontServiceSecurityGroupId', 'SubnetAz', 'SubnetId', 'VpcId',
  ]);
  for (const key of ['AmiId', 'CloudFrontPrefixListId', 'SubnetAz', 'SubnetId', 'VpcId']) {
    assert.equal('Default' in foundation.Parameters[key], false);
  }
  const prohibited = /AWS::(?:ElasticLoadBalancingV2|RDS|Route53|CertificateManager)::|AWS::EC2::(?:VPC|Subnet|NatGateway|EIP)$/;
  for (const template of [foundation, edge]) {
    for (const resource of Object.values(template.Resources)) assert.doesNotMatch(resource.Type, prohibited);
  }
});

test('single x86 t3.small cannot incur unlimited CPU credits and has protected IMDSv2', () => {
  const instance = props('AppInstance');
  assert.equal(instance.InstanceType, 't3.small');
  assert.deepEqual(instance.ImageId, ref('AmiId'));
  assert.deepEqual(instance.CreditSpecification, { CPUCredits: 'standard' });
  assert.equal(instance.DisableApiTermination, true);
  assert.equal('KeyName' in instance, false);
  assert.deepEqual(instance.MetadataOptions, {
    HttpEndpoint: 'enabled', HttpTokens: 'required', HttpPutResponseHopLimit: 1, InstanceMetadataTags: 'disabled',
  });
  assert.deepEqual(instance.NetworkInterfaces, [{
    DeviceIndex: '0', AssociatePublicIpAddress: true, DeleteOnTermination: true,
    SubnetId: ref('SubnetId'), GroupSet: [ref('AppSecurityGroup')],
  }]);
});

test('root disk and separate encrypted database disk have distinct lifecycles', () => {
  assert.deepEqual(props('AppInstance').BlockDeviceMappings, [{
    DeviceName: '/dev/xvda', Ebs: { VolumeType: 'gp3', VolumeSize: 20, Encrypted: true, DeleteOnTermination: true },
  }]);
  assert.equal(props('DatabaseVolume').Size, 8);
  assert.equal(props('DatabaseVolume').VolumeType, 'gp3');
  assert.equal(props('DatabaseVolume').Encrypted, true);
  assert.deepEqual(props('DatabaseVolume').AvailabilityZone, ref('SubnetAz'));
  assert.equal(resources.DatabaseVolume.DeletionPolicy, 'Retain');
  assert.equal(resources.DatabaseVolume.UpdateReplacePolicy, 'Retain');
  assert.deepEqual(props('DatabaseVolumeAttachment'), {
    Device: '/dev/sdf', InstanceId: ref('AppInstance'), VolumeId: ref('DatabaseVolume'),
  });
});

test('only CloudFront port 80 ingress is possible, and the later service group replaces the prefix list', () => {
  const group = props('AppSecurityGroup');
  assert.equal(group.SecurityGroupIngress.length, 1);
  const [condition, serviceGroup, prefixList] = group.SecurityGroupIngress[0]['Fn::If'];
  assert.equal(condition, 'UseCloudFrontServiceGroup');
  assert.deepEqual(foundation.Conditions.UseCloudFrontServiceGroup, {
    'Fn::Not': [{ 'Fn::Equals': [ref('CloudFrontServiceSecurityGroupId'), ''] }],
  });
  for (const rule of [serviceGroup, prefixList]) {
    assert.equal(rule.IpProtocol, 'tcp');
    assert.equal(rule.FromPort, 80);
    assert.equal(rule.ToPort, 80);
    assert.equal('CidrIp' in rule, false);
    assert.equal('CidrIpv6' in rule, false);
  }
  assert.deepEqual(serviceGroup.SourceSecurityGroupId, ref('CloudFrontServiceSecurityGroupId'));
  assert.equal('SourcePrefixListId' in serviceGroup, false);
  assert.deepEqual(prefixList.SourcePrefixListId, ref('CloudFrontPrefixListId'));
  assert.equal('SourceSecurityGroupId' in prefixList, false);
  assert.deepEqual(group.SecurityGroupEgress.map(({ IpProtocol, FromPort, ToPort, CidrIp }) => ({ IpProtocol, FromPort, ToPort, CidrIp })),
    [{ IpProtocol: 'tcp', FromPort: 443, ToPort: 443, CidrIp: '0.0.0.0/0' }]);
});

test('ECR is private, immutable, scanned and retained without automatic image expiry', () => {
  const repository = props('ImageRepository');
  assert.equal(resources.ImageRepository.Type, 'AWS::ECR::Repository');
  assert.equal(repository.ImageTagMutability, 'IMMUTABLE');
  assert.deepEqual(repository.ImageScanningConfiguration, { ScanOnPush: true });
  assert.deepEqual(repository.EncryptionConfiguration, { EncryptionType: 'AES256' });
  assert.equal('LifecyclePolicy' in repository, false);
  assert.equal('RepositoryPolicyText' in repository, false);
  assert.equal(resources.ImageRepository.DeletionPolicy, 'Retain');
  assert.equal(resources.ImageRepository.UpdateReplacePolicy, 'Retain');
});

test('S3 retains private encrypted versions and explicitly denies plaintext transport', () => {
  const bucket = props('ArtifactBackupBucket');
  assert.deepEqual(bucket.BucketEncryption, {
    ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }],
  });
  assert.deepEqual(bucket.VersioningConfiguration, { Status: 'Enabled' });
  assert.deepEqual(bucket.OwnershipControls, { Rules: [{ ObjectOwnership: 'BucketOwnerEnforced' }] });
  assert.deepEqual(bucket.PublicAccessBlockConfiguration, {
    BlockPublicAcls: true, IgnorePublicAcls: true, BlockPublicPolicy: true, RestrictPublicBuckets: true,
  });
  assert.equal(resources.ArtifactBackupBucket.DeletionPolicy, 'Retain');
  assert.equal(resources.ArtifactBackupBucket.UpdateReplacePolicy, 'Retain');
  assert.equal('WebsiteConfiguration' in bucket, false);
  assert.equal('LifecycleConfiguration' in bucket, false);
  assert.deepEqual(props('ArtifactBackupBucketPolicy').PolicyDocument.Statement, [{
    Sid: 'DenyInsecureTransport', Effect: 'Deny', Principal: '*', Action: 's3:*',
    Resource: [att('ArtifactBackupBucket', 'Arn'), sub('${ArtifactBackupBucket.Arn}/*')],
    Condition: { Bool: { 'aws:SecureTransport': 'false' } },
  }]);
});

test('instance may pull only its repository and write only the backup prefix', () => {
  const allowed = policy.filter((entry) => entry.Effect === 'Allow');
  assert.deepEqual(allowed.filter((entry) => entry.Resource === '*').map((entry) => entry.Action), [['ecr:GetAuthorizationToken']]);
  assert.deepEqual(statement('PullOwnImages').Resource, att('ImageRepository', 'Arn'));
  assert.deepEqual(statement('PullOwnImages').Action.sort(), ['ecr:BatchCheckLayerAvailability', 'ecr:BatchGetImage', 'ecr:GetDownloadUrlForLayer']);
  assert.deepEqual(statement('WriteBackupsOnly').Action, ['s3:PutObject']);
  assert.deepEqual(statement('WriteBackupsOnly').Resource, sub('${ArtifactBackupBucket.Arn}/backups/*'));
  assert.deepEqual(statement('ListBackupsOnly').Resource, att('ArtifactBackupBucket', 'Arn'));
  assert.deepEqual(statement('ListBackupsOnly').Condition, { StringLike: { 's3:prefix': ['backups/', 'backups/*'] } });
  assert.deepEqual(statement('ReadRuntimeArtifactsOnly').Action, ['s3:GetObject']);
  assert.deepEqual(statement('ReadRuntimeArtifactsOnly').Resource, sub('${ArtifactBackupBucket.Arn}/artifacts/*'));
  assert.equal(allowed.flatMap((entry) => entry.Action).some((action) => /Delete|PutImage|UploadLayer|Create|s3:\*/.test(action)), false);
});

test('SSM agent keeps its core policy but its broad parameter read grant is explicitly narrowed', () => {
  assert.deepEqual(props('InstanceRole').ManagedPolicyArns, [sub('arn:${AWS::Partition}:iam::aws:policy/AmazonSSMManagedInstanceCore')]);
  assert.deepEqual(props('InstanceRole').AssumeRolePolicyDocument.Statement, [{
    Effect: 'Allow', Principal: { Service: 'ec2.amazonaws.com' }, Action: 'sts:AssumeRole',
  }]);
  const parameterArn = sub('arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:parameter/worldcup/production/*');
  assert.deepEqual(statement('ReadWorldCupParameters').Resource, parameterArn);
  assert.deepEqual(statement('ReadWorldCupParameters').Action, ['ssm:GetParameter', 'ssm:GetParameters']);
  assert.equal(statement('DenyOtherParameterReads').Effect, 'Deny');
  assert.deepEqual(statement('DenyOtherParameterReads').NotResource, parameterArn);
  assert.deepEqual(statement('DenyOtherParameterReads').Action, [
    'ssm:GetParameter', 'ssm:GetParameters', 'ssm:GetParametersByPath', 'ssm:GetParameterHistory',
  ]);
});

test('userdata contains only package/service bootstrap and cannot format storage or inject secrets', () => {
  const script = props('AppInstance').UserData['Fn::Base64'];
  assert.equal(script, '#!/bin/bash\nset -euo pipefail\ndnf install -y docker amazon-ssm-agent\nsystemctl enable --now docker\nsystemctl enable --now amazon-ssm-agent\n');
  assert.doesNotMatch(script, /mkfs|wipefs|fdisk|mount |get-parameter|OPENAI|PASSWORD|OriginVerifySecret|curl|wget/);
});

test('edge uses the running EC2 private VPC origin and an unexposed secret header', () => {
  const origin = edge.Resources.VpcOrigin.Properties.VpcOriginEndpointConfig;
  assert.deepEqual(origin.Arn, ref('InstanceArn'));
  assert.equal(origin.OriginProtocolPolicy, 'http-only');
  assert.equal(origin.HTTPPort, 80);
  assert.equal(origin.IpAddressType, 'ipv4');
  assert.deepEqual(distribution.Origins[0].DomainName, ref('InstancePrivateDnsName'));
  assert.deepEqual(distribution.Origins[0].VpcOriginConfig.VpcOriginId, att('VpcOrigin', 'Id'));
  assert.equal('CustomOriginConfig' in distribution.Origins[0], false);
  assert.deepEqual(distribution.Origins[0].OriginCustomHeaders, [{ HeaderName: 'X-Origin-Verify', HeaderValue: ref('OriginVerifySecret') }]);
  assert.equal(edge.Parameters.OriginVerifySecret.NoEcho, true);
  assert.equal(edge.Parameters.OriginVerifySecret.MinLength, 32);
  assert.equal('Default' in edge.Parameters.OriginVerifySecret, false);
  assert.doesNotMatch(JSON.stringify(edge.Outputs), /OriginVerifySecret|HeaderValue/);
});

test('HTTPS edge disables caching for all routes while forwarding all cookies/query and origin-check headers', () => {
  const behavior = distribution.DefaultCacheBehavior;
  assert.deepEqual(distribution.ViewerCertificate, { CloudFrontDefaultCertificate: true });
  assert.equal(distribution.Enabled, true);
  assert.equal('Aliases' in distribution, false);
  assert.equal(behavior.ViewerProtocolPolicy, 'redirect-to-https');
  assert.deepEqual(behavior.AllowedMethods, ['DELETE', 'GET', 'HEAD', 'OPTIONS', 'PATCH', 'POST', 'PUT']);
  assert.equal(behavior.CachePolicyId, '4135ea2d-6df8-44a3-9df3-4b5a84be39ad'); // AWS managed CachingDisabled, all TTLs 0.
  assert.equal(behavior.OriginRequestPolicyId, 'b689b0a8-53d0-40ab-baf2-68738e2966ac'); // AWS AllViewerExceptHostHeader: all cookies/query.
  assert.equal('CacheBehaviors' in distribution, false);
  assert.equal('ForwardedValues' in behavior, false);
  assert.equal('DefaultRootObject' in distribution, false);
  assert.equal('FunctionAssociations' in behavior, false);
  for (const error of distribution.CustomErrorResponses) {
    assert.equal(error.ErrorCachingMinTTL, 0);
    assert.equal('ResponsePagePath' in error, false);
    assert.equal('ResponseCode' in error, false);
  }
  assert.deepEqual(distribution.CustomErrorResponses.map((error) => error.ErrorCode), [400, 403, 404, 405, 414, 416, 500, 501, 502, 503, 504]);
});

test('owned taggable resources identify the project and output references are nonsecret', () => {
  for (const name of ['AppSecurityGroup', 'ImageRepository', 'ArtifactBackupBucket', 'InstanceRole', 'AppInstance', 'DatabaseVolume']) {
    assert(props(name).Tags.some((tag) => tag.Key === 'Project' && tag.Value === 'dynamic-ai-world-cup'));
  }
  for (const resource of Object.values(edge.Resources)) {
    assert(resource.Properties.Tags.some((tag) => tag.Key === 'Project' && tag.Value === 'dynamic-ai-world-cup'));
  }
  assert.deepEqual(foundation.Outputs.DatabaseVolumeId.Value, ref('DatabaseVolume'));
  assert.deepEqual(foundation.Outputs.ImageRepositoryUri.Value, att('ImageRepository', 'RepositoryUri'));
  assert.deepEqual(foundation.Outputs.InstancePrivateDnsName.Value, att('AppInstance', 'PrivateDnsName'));
  assert.doesNotMatch(JSON.stringify(foundation.Outputs), /password|secret|api.key/i);
});
