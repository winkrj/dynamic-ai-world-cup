# AWS stack boundaries

These templates describe new project resources only. They do not prove account eligibility, a successful deployment, a mounted database, an available provider, or a tested restore.

Account compatibility: the first real VPC-origin creation rejected optional `IpAddressType` even though CloudFormation template validation accepted it. The template omits that account-gated property and uses the standard VPC-origin contract; it does not fall back to a public custom origin. Validate-template checks syntax, not account feature availability.

1. Before creating anything, confirm the current AWS account/region/Free plan, credit expiry and existing workloads. Verify the selected VPC has an Internet Gateway, the subnet has IPv4 HTTPS egress and free addresses, and `SubnetAz` is that exact subnet's AZ. Resolve an Amazon Linux 2023 x86_64 AMI with `/dev/xvda` root and the regional CloudFront managed prefix list. All identifiers are required parameters without account-specific defaults.
2. Apply `foundation.json` with IAM capability and stack termination protection. It creates one t3.small with standard CPU credits, encrypted 20 GiB root, an independently retained encrypted 8 GiB DB volume, private ECR and one retained private S3 bucket. Public IPv4 is for outbound SSM/ECR/S3/OpenAI access without NAT; it is not unrestricted inbound access. The security group admits only CloudFront port 80 and outbound HTTPS. There is no SSH key/port, ALB, NAT, RDS or custom domain.
3. Wait for instance status checks and SSM Online. Userdata installs/enables Docker and the SSM agent only. It never installs an application, accesses secrets, formats or mounts a disk. Resolve the attached EBS volume by its actual volume ID/NVMe serial, not by a guessed `/dev/nvme*` name. Verify the filesystem before any initialization; never format a disk with an existing filesystem. The operator separately mounts this retained volume at `/var/lib/worldcup`; runtime startup must fail if that mount is absent. Do not detach a mounted/running database.
4. Pin and verify runtime image and Compose binary digests separately. Runtime lives under `/opt/worldcup/runtime`. Secrets remain in root-owned mode-600 files under `/etc/worldcup` (`app.env`, `postgres.env`, `proxy.env`); `runtime.env` carries deployment metadata. Use the existing runtime contract. Application/DB containers cannot access the host instance role through IMDSv2 hop limit 1. Do not place secrets in userdata, image layers, CloudFormation metadata, command history or Git.
5. Create `edge.json` only after the EC2 instance is running, supplying the foundation's InstanceArn/InstancePrivateDnsName. Generate the origin verification value privately and install the same value in proxy.env. `NoEcho` masks stack parameters, not every service API: CloudFront configuration readers can retrieve custom header values, so restrict that read access too. Never include the real value in a checked-in parameter file or output.
6. Once the VPC origin exists, resolve the AWS-created `CloudFront-VPCOrigins-Service-SG` in the same VPC and update the foundation parameter `CloudFrontServiceSecurityGroupId`. This replaces the bootstrap prefix-list rule; do not modify or impersonate the AWS-managed group. The project custom header still distinguishes its origin requests.
7. Configure the application with the resulting HTTPS CloudFront origin, then check trusted viewer-IP handling, readiness, secure cookies, game state and database backup/restore. Distribution caching is disabled for every route initially. All viewer cookies/query/header values except Host are forwarded; API errors are not rewritten into HTML or cached. No actual API generation is authorized by these templates.

## Storage and permission boundaries

- The instance can pull only this ECR repository. GetAuthorizationToken alone requires Resource `*`; upload/delete/expiry permissions are not granted. Immutable tags do not replace digest pinning.
- One versioned, encrypted S3 bucket uses `backups/` and `artifacts/`. The instance may PutObject and ListBucket only for backups, and GetObject only for artifacts. It cannot delete backups, upload artifacts, or read backups for a restore. A separately authorized operator performs restore/reconciliation.
- AmazonSSMManagedInstanceCore contains broad GetParameter/GetParameters permissions. The explicit NotResource deny restricts those reads to `/worldcup/production/*`. Read parameters by exact names; GetParametersByPath is not granted. Use the default SSM encryption key or separately review any customer-managed KMS key policy/permissions.
- EBS, ECR and S3 retention preserves data when deleting/replacing resources; it does not stop storage/credit consumption, create backups, or reattach a retained volume automatically. Review changesets for replacement and explicit data migration. Protect the foundation stack against deletion as well as the instance against termination.
- Stack outputs contain resource identifiers, not secret values. Keep actual account inventory and deployed parameter values out of the public repository.

## Validation

Offline: `node --test scripts/check-aws-deployment.test.mjs`.

Also validate each JSON template with AWS CloudFormation before creating a changeset. Syntax/static checks do not establish AMI compatibility, AZ/network correctness, account permission, service readiness or production behavior.

Primary references checked 2026-09-19:

- [VPC-origin prerequisites and security-group tightening](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html)
- [VpcOrigin CloudFormation endpoint properties](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-cloudfront-vpcorigin-vpcoriginendpointconfig.html)
- [Distribution VpcOriginConfig](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-cloudfront-distribution-vpcoriginconfig.html)
- [CachingDisabled policy](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html)
- [AllViewerExceptHostHeader policy](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-origin-request-policies.html)
- [SSM managed policy permissions](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonSSMManagedInstanceCore.html)
