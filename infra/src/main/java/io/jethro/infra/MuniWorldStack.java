package io.jethro.infra;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.BlockDevice;
import software.amazon.awscdk.services.ec2.BlockDeviceVolume;
import software.amazon.awscdk.services.ec2.CfnEIP;
import software.amazon.awscdk.services.ec2.CfnEIPAssociation;
import software.amazon.awscdk.services.ec2.EbsDeviceOptions;
import software.amazon.awscdk.services.ec2.EbsDeviceVolumeType;
import software.amazon.awscdk.services.ec2.Instance;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.MachineImage;
import software.amazon.awscdk.services.ec2.OperatingSystemType;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SsmParameterImageOptions;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.UserData;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * muni-world's OWN single-node stack (muni ADR-0021) — deliberately parallel to {@link JethroDevStack}
 * but sharing NO resources with it: its own VPC, node, ECR repo, IAM roles and S3 backup bucket, so
 * deploying or destroying either system never touches the other, and `project=muni-world` tags make it
 * a separate cost line (ADR-0011 pattern).
 *
 * <p>Differences from the trading stack, each decided in muni ADR-0021:
 * <ul>
 *   <li><b>ARM + small:</b> default t4g.small — the workload is one ≤512MiB-heap jar + Postgres.</li>
 *   <li><b>ALWAYS ON</b> — no stop-when-idle schedules: muni-world's value is its daily ingest cadence
 *       (curve refresh, fund pass), and a stopped node silently skips ingests. ~2% of the trading
 *       node's cost.</li>
 *   <li><b>S3 backups bucket</b> (versioned, RETAIN): nightly pg_dump + the irreplaceable OS PDFs.</li>
 *   <li><b>No budget here</b> — the account-wide budget alarm lives in JethroDev (one cap per account).</li>
 * </ul>
 */
public class MuniWorldStack extends Stack {

    public MuniWorldStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String githubRepo = ctx("githubRepo", "teoleg/jethro");
        String githubBranch = ctx("muniGithubBranch", "master");
        // t4g.small: 2 vCPU / 2 GiB Graviton. AWS's published on-demand rate (~$12-14/mo + ~$1.60 for
        // 20GiB gp3) — the price is AWS's, the SIZE is the owner's dial: -c muniInstanceType=...
        String instanceTypeStr = ctx("muniInstanceType", "t4g.small");

        // ---- ECR: muni-world's own image repo ----
        Repository repo = Repository.Builder.create(this, "MuniRepo")
                .repositoryName("muni-world")
                .imageScanOnPush(true)
                .lifecycleRules(List.of(LifecycleRule.builder().maxImageCount(10).build()))
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // ---- S3: the backup bucket — versioned and RETAINed, so even a stack teardown keeps data ----
        Bucket backups = Bucket.Builder.create(this, "MuniBackups")
                .versioned(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // ---- Network: one public subnet, no NAT (public IP; same cost posture as JethroDev) ----
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(1)
                .natGateways(0)
                .subnetConfiguration(List.of(SubnetConfiguration.builder()
                        .name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build()))
                .build();

        SecurityGroup edgeSg = SecurityGroup.Builder.create(this, "EdgeSg")
                .vpc(vpc)
                .allowAllOutbound(true)
                .description("muni-world edge: HTTP/HTTPS only (Caddy basic-auth); SSM needs no inbound")
                .build();
        edgeSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "ACME HTTP-01 challenge + HTTPS redirect");
        edgeSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS (Caddy basic-auth gates the UI)");

        // ---- Instance role: SSM managed + ECR pull + /muni/prod/* params + the backup bucket ----
        Role nodeRole = Role.Builder.create(this, "NodeRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")))
                .build();
        nodeRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"))
                .resources(List.of("arn:aws:ssm:" + getRegion() + ":" + getAccount()
                        + ":parameter/muni/prod/*"))
                .build());
        Map<String, Object> viaSsm = Map.of("StringEquals",
                Map.of("kms:ViaService", "ssm." + getRegion() + ".amazonaws.com"));
        nodeRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt"))
                .resources(List.of("*"))
                .conditions(viaSsm)
                .build());
        backups.grantReadWrite(nodeRole);

        // ---- The host: Ubuntu 24.04 ARM64, 20GiB encrypted gp3 root ----
        Instance node = Instance.Builder.create(this, "Node")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .instanceType(new InstanceType(instanceTypeStr))
                .machineImage(MachineImage.fromSsmParameter(
                        // Canonical's public parameter for Ubuntu 24.04 arm64 (gp3) — ARM to match t4g.
                        "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id",
                        SsmParameterImageOptions.builder().os(OperatingSystemType.LINUX).build()))
                .role(nodeRole)
                .securityGroup(edgeSg)
                .blockDevices(List.of(BlockDevice.builder()
                        .deviceName("/dev/sda1")
                        .volume(BlockDeviceVolume.ebs(20, EbsDeviceOptions.builder()
                                .volumeType(EbsDeviceVolumeType.GP3).encrypted(true).build()))
                        .build()))
                .userData(userData(githubRepo, githubBranch))
                .build();

        CfnEIP eip = CfnEIP.Builder.create(this, "Eip").domain("vpc").build();
        CfnEIPAssociation.Builder.create(this, "EipAssoc")
                .allocationId(eip.getAttrAllocationId())
                .instanceId(node.getInstanceId())
                .build();

        // ---- Deploy permissions: attached ADDITIVELY to jethro's EXISTING deploy role (owner
        // directive: "use the same vars from the jethro setup" — AWS_DEPLOY_ROLE_ARN and AWS_REGION
        // were configured once and stay the only GitHub config). JethroDev's template is untouched;
        // this stack attaches one extra inline policy to the role by name. The muni workflows find
        // the muni node AT DEPLOY TIME by its project=muni-world tag, so no instance-id variable
        // exists either — ec2:DescribeInstances (read-only) enables that lookup. ----
        IRole deployRole = Role.fromRoleName(this, "JethroDeployRole", "jethro-deploy");
        repo.grantPullPush(deployRole);
        deployRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:SendCommand"))
                .resources(List.of(
                        "arn:aws:ec2:" + getRegion() + ":" + getAccount() + ":instance/" + node.getInstanceId(),
                        "arn:aws:ssm:" + getRegion() + "::document/AWS-RunShellScript"))
                .build());
        deployRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:DescribeInstances"))
                .resources(List.of("*"))       // DescribeInstances does not support resource scoping
                .build());

        // ---- Non-secret SSM parameters. SecureStrings (POSTGRES_PASSWORD, MUNI_BASIC_AUTH_HASH,
        //      MUNI_CONTACT_EMAIL) are set out-of-band — the contact email is the SEC fair-access
        //      address and never enters git, same rule as always. ----
        ssmString("DomainParam", "/muni/prod/MUNI_DOMAIN", "muni.example.com");
        ssmString("BasicAuthUserParam", "/muni/prod/MUNI_BASIC_AUTH_USER", "oleg");
        ssmString("BackupBucketParam", "/muni/prod/MUNI_BACKUP_BUCKET", backups.getBucketName());

        // ---- Outputs: what the runbook + GitHub variables need ----
        out("MuniEcrRepositoryUri", repo.getRepositoryUri());
        out("MuniInstanceId", node.getInstanceId());
        out("MuniElasticIp", eip.getRef());
        out("MuniBackupBucket", backups.getBucketName());
        out("SecretsReminder", "Set SecureString SSM params before first deploy: "
                + "/muni/prod/POSTGRES_PASSWORD, /muni/prod/MUNI_BASIC_AUTH_HASH, "
                + "/muni/prod/MUNI_CONTACT_EMAIL (SEC fair-access contact)");
    }

    /** User-data: run the checked-in muni bootstrap (reads ../muni-world/deploy/bootstrap.sh at synth). */
    private UserData userData(String githubRepo, String githubBranch) {
        String bootstrap;
        try {
            bootstrap = Files.readString(Path.of("..", "muni-world", "deploy", "bootstrap.sh"));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "run cdk from infra/ so ../muni-world/deploy/bootstrap.sh is readable", e);
        }
        UserData ud = UserData.forLinux();
        ud.addCommands(
                "export MUNI_REPO_URL=https://github.com/" + githubRepo + ".git",
                "export MUNI_REPO_REF=" + githubBranch,
                // Private-repo clone: reuse the same GITHUB_TOKEN SecureString convention if present.
                "TOKEN=$(aws ssm get-parameter --with-decryption --name /muni/prod/GITHUB_TOKEN "
                        + "--query Parameter.Value --output text 2>/dev/null || true)",
                "if [ -n \"$TOKEN\" ] && [ \"$TOKEN\" != \"None\" ]; then "
                        + "export MUNI_REPO_URL=https://x-access-token:${TOKEN}@github.com/"
                        + githubRepo + ".git; fi",
                bootstrap);
        return ud;
    }

    private String ctx(String key, String dflt) {
        Object v = getNode().tryGetContext(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private void ssmString(String id, String name, String value) {
        StringParameter.Builder.create(this, id).parameterName(name).stringValue(value).build();
    }

    private void out(String name, String value) {
        CfnOutput.Builder.create(this, name).value(value).build();
    }
}
