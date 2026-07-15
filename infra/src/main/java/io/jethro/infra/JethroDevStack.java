package io.jethro.infra;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.ec2.BlockDevice;
import software.amazon.awscdk.services.ec2.BlockDeviceVolume;
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
import software.amazon.awscdk.services.ec2.CfnEIP;
import software.amazon.awscdk.services.ec2.CfnEIPAssociation;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.iam.IOpenIdConnectProvider;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.OpenIdConnectPrincipal;
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.scheduler.CfnSchedule;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The single-node dev stack (ADR-0013), codifying the {@code deploy/README.md} runbook:
 *
 * <ul>
 *   <li>a minimal public-subnet VPC (no NAT — cost) and an edge security group (80/443 only;
 *       SSM needs no inbound);</li>
 *   <li>an EC2 host (default m6i.xlarge, Ubuntu 24.04) whose user-data runs
 *       {@code deploy/bootstrap.sh}, with an Elastic IP and a gp3 root volume;</li>
 *   <li>the instance role (SSM managed + ECR pull + read of {@code /jethro/prod/*});</li>
 *   <li>the ECR repo for the app image and the GitHub OIDC deploy role the
 *       {@code Deploy} workflow assumes (ECR push + SSM SendCommand to this instance);</li>
 *   <li>stop-when-idle schedules (EventBridge Scheduler → EC2 stop/start) and an optional
 *       monthly cost budget alarm (ADR-0011);</li>
 *   <li>the non-secret SSM parameters — SecureStrings (DB password, basic-auth hash, tokens)
 *       are set out-of-band and intentionally NOT in the template.</li>
 * </ul>
 */
public class JethroDevStack extends Stack {

    public JethroDevStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String githubRepo = ctx("githubRepo", "teoleg/jethro");
        String githubBranch = ctx("githubBranch", "claude/new-session-smb8v6");
        String instanceTypeStr = ctx("instanceType", "t3a.xlarge");
        String alertEmail = ctx("alertEmail", "");
        String budgetUsd = ctx("monthlyBudgetUsd", "100");
        boolean createOidc = Boolean.parseBoolean(ctx("createOidcProvider", "true"));

        // ---- ECR: the app image the deploy workflow builds and the node pulls ----
        Repository appRepo = Repository.Builder.create(this, "AppRepo")
                .repositoryName("jethro-app")
                .imageScanOnPush(true)
                .lifecycleRules(List.of(LifecycleRule.builder().maxImageCount(15).build()))
                .removalPolicy(RemovalPolicy.RETAIN) // don't delete images if the stack is torn down
                .build();

        // ---- Network: one public subnet, no NAT (the node has a public IP; cost posture) ----
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(1)
                .natGateways(0)
                .subnetConfiguration(List.of(SubnetConfiguration.builder()
                        .name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build()))
                .build();

        SecurityGroup edgeSg = SecurityGroup.Builder.create(this, "EdgeSg")
                .vpc(vpc)
                .allowAllOutbound(true)
                .description("Jethro edge: HTTP/HTTPS only; SSM needs no inbound port")
                .build();
        edgeSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "ACME HTTP-01 challenge + HTTPS redirect");
        edgeSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS (Caddy basic-auth gates the app)");

        // ---- Instance role: SSM managed + ECR pull + read the /jethro/prod/* params ----
        Role nodeRole = Role.Builder.create(this, "NodeRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")))
                .build();
        nodeRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"))
                .resources(List.of("arn:aws:ssm:" + getRegion() + ":" + getAccount() + ":parameter/jethro/prod/*"))
                .build());
        // Decrypt SecureString params via the AWS-managed SSM key (scoped by the ssm service).
        Map<String, Object> viaSsm = Map.of("StringEquals",
                Map.of("kms:ViaService", "ssm." + getRegion() + ".amazonaws.com"));
        nodeRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("kms:Decrypt"))
                .resources(List.of("*"))
                .conditions(viaSsm)
                .build());

        // ---- The host ----
        Instance node = Instance.Builder.create(this, "Node")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .instanceType(new InstanceType(instanceTypeStr))
                .machineImage(MachineImage.fromSsmParameter(
                        // Canonical's public parameter for Ubuntu 24.04 amd64 (gp3).
                        "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id",
                        SsmParameterImageOptions.builder().os(OperatingSystemType.LINUX).build()))
                .role(nodeRole)
                .securityGroup(edgeSg)
                .blockDevices(List.of(BlockDevice.builder()
                        .deviceName("/dev/sda1") // Ubuntu root device
                        .volume(BlockDeviceVolume.ebs(100, EbsDeviceOptions.builder()
                                .volumeType(EbsDeviceVolumeType.GP3).encrypted(true).build()))
                        .build()))
                .userData(userData(githubRepo, githubBranch))
                .build();

        CfnEIP eip = CfnEIP.Builder.create(this, "Eip").domain("vpc").build();
        CfnEIPAssociation.Builder.create(this, "EipAssoc")
                .allocationId(eip.getAttrAllocationId())
                .instanceId(node.getInstanceId())
                .build();

        // ---- GitHub OIDC deploy role (assumed by the Deploy workflow) ----
        IOpenIdConnectProvider oidc = createOidc
                ? OpenIdConnectProvider.Builder.create(this, "GithubOidc")
                        .url("https://token.actions.githubusercontent.com")
                        .clientIds(List.of("sts.amazonaws.com"))
                        .build()
                : OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc",
                        "arn:aws:iam::" + getAccount() + ":oidc-provider/token.actions.githubusercontent.com");

        Map<String, Object> githubTrust = Map.of(
                "StringEquals", Map.of("token.actions.githubusercontent.com:aud", "sts.amazonaws.com"),
                "StringLike", Map.of("token.actions.githubusercontent.com:sub", "repo:" + githubRepo + ":*"));
        OpenIdConnectPrincipal githubPrincipal = new OpenIdConnectPrincipal(oidc, githubTrust);

        Role deployRole = Role.Builder.create(this, "GithubDeployRole")
                .roleName("jethro-deploy")
                .assumedBy(githubPrincipal)
                .description("GitHub Actions (OIDC): push the app image + trigger the SSM rollout")
                .build();
        appRepo.grantPullPush(deployRole);
        deployRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:SendCommand"))
                .resources(List.of(
                        "arn:aws:ec2:" + getRegion() + ":" + getAccount() + ":instance/" + node.getInstanceId(),
                        "arn:aws:ssm:" + getRegion() + "::document/AWS-RunShellScript"))
                .build());
        deployRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ssm:GetCommandInvocation", "ssm:ListCommands", "ssm:ListCommandInvocations"))
                .resources(List.of("*"))
                .build());

        // ---- Non-secret SSM parameters (SecureStrings set out-of-band, see README) ----
        ssmString("ProviderParam", "/jethro/prod/JETHRO_TRADING_PROVIDER", "sim");
        ssmString("ModelParam", "/jethro/prod/JETHRO_AI_MODEL", "qwen2.5:1.5b");
        ssmString("DomainParam", "/jethro/prod/JETHRO_DOMAIN", "jethro.example.com");
        ssmString("BasicAuthUserParam", "/jethro/prod/JETHRO_BASIC_AUTH_USER", "oleg");

        // ---- Stop-when-idle: nightly stop, weekday-morning start (ADR-0013) ----
        Role schedulerRole = Role.Builder.create(this, "SchedulerRole")
                .assumedBy(new ServicePrincipal("scheduler.amazonaws.com"))
                .build();
        schedulerRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:StopInstances", "ec2:StartInstances"))
                .resources(List.of("arn:aws:ec2:" + getRegion() + ":" + getAccount() + ":instance/" + node.getInstanceId()))
                .build());
        instanceSchedule("StopNightly", "cron(0 3 ? * * *)", "stopInstances", node.getInstanceId(), schedulerRole);
        instanceSchedule("StartWeekdays", "cron(0 12 ? * MON-FRI *)", "startInstances", node.getInstanceId(), schedulerRole);

        // ---- Monthly cost budget (ADR-0011) — ACCOUNT-WIDE, the single cost control point for
        // this account (the other demo project is negligible/rarely on, so one cap over
        // everything is the simplest ceiling). Alerts at 50/80/100% actual + a forecast-over-100%
        // warning; Budgets ALERTS but does not auto-stop, so the real cap is the stop-when-idle
        // schedule above (the alarms are the backstop). Requires an alert email.
        if (!alertEmail.isBlank()) {
            CfnBudget.Builder.create(this, "MonthlyBudget")
                    .budget(CfnBudget.BudgetDataProperty.builder()
                            .budgetType("COST").timeUnit("MONTHLY")
                            .budgetLimit(CfnBudget.SpendProperty.builder()
                                    .amount(Double.parseDouble(budgetUsd)).unit("USD").build())
                            .build())
                    .notificationsWithSubscribers(List.of(
                            budgetAlert("ACTUAL", 50.0, alertEmail),
                            budgetAlert("ACTUAL", 80.0, alertEmail),
                            budgetAlert("ACTUAL", 100.0, alertEmail),
                            budgetAlert("FORECASTED", 100.0, alertEmail)))
                    .build();
        }

        // ---- Outputs: the values the runbook + GitHub variables need ----
        out("EcrRepositoryUri", appRepo.getRepositoryUri());
        out("DeployRoleArn", deployRole.getRoleArn());
        out("InstanceId", node.getInstanceId());
        out("ElasticIp", eip.getRef());
        out("SecretsReminder", "Set SecureString SSM params before first deploy: "
                + "/jethro/prod/POSTGRES_PASSWORD, /JETHRO_BASIC_AUTH_HASH"
                + (githubRepo.isEmpty() ? "" : ", /GITHUB_TOKEN (private-repo clone)")
                + " (and /FINNHUB_TOKEN if provider=finnhub)");
    }

    /** User-data: fetch an optional GitHub token from SSM (private-repo clone), then run the
     *  checked-in bootstrap. Reads {@code ../deploy/bootstrap.sh} at synth (cdk runs in infra/). */
    private UserData userData(String githubRepo, String githubBranch) {
        String bootstrap;
        try {
            bootstrap = Files.readString(Path.of("..", "deploy", "bootstrap.sh"));
        } catch (IOException e) {
            throw new IllegalStateException("run cdk from infra/ so ../deploy/bootstrap.sh is readable", e);
        }
        String prelude = String.join("\n",
                "#!/usr/bin/env bash",
                "set -euo pipefail",
                "export JETHRO_REPO_REF=\"" + githubBranch + "\"",
                "REGION=$(curl -s http://169.254.169.254/latest/meta-data/placement/region)",
                "apt-get update -y && apt-get install -y awscli curl git || true",
                "TOKEN=$(aws ssm get-parameter --region \"$REGION\" --name /jethro/prod/GITHUB_TOKEN "
                        + "--with-decryption --query Parameter.Value --output text 2>/dev/null || echo '')",
                "if [ -n \"$TOKEN\" ] && [ \"$TOKEN\" != \"None\" ]; then",
                "  export JETHRO_REPO_URL=\"https://x-access-token:${TOKEN}@github.com/" + githubRepo + ".git\"",
                "else",
                "  export JETHRO_REPO_URL=\"https://github.com/" + githubRepo + ".git\"",
                "fi",
                "");
        return UserData.custom(prelude + "\n" + bootstrap);
    }

    private CfnBudget.NotificationWithSubscribersProperty budgetAlert(String type, double pct, String email) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder()
                        .notificationType(type).comparisonOperator("GREATER_THAN")
                        .threshold(pct).thresholdType("PERCENTAGE").build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                        .subscriptionType("EMAIL").address(email).build()))
                .build();
    }

    private void instanceSchedule(String id, String cron, String apiAction, String instanceId, Role role) {
        CfnSchedule.Builder.create(this, id)
                .flexibleTimeWindow(CfnSchedule.FlexibleTimeWindowProperty.builder().mode("OFF").build())
                .scheduleExpression(cron)
                .scheduleExpressionTimezone("UTC")
                .target(CfnSchedule.TargetProperty.builder()
                        .arn("arn:aws:scheduler:::aws-sdk:ec2:" + apiAction)
                        .roleArn(role.getRoleArn())
                        .input("{\"InstanceIds\":[\"" + instanceId + "\"]}")
                        .build())
                .build();
    }

    private void ssmString(String id, String name, String value) {
        StringParameter.Builder.create(this, id).parameterName(name).stringValue(value).build();
    }

    private void out(String id, String value) {
        CfnOutput.Builder.create(this, id).value(value).build();
    }

    private String ctx(String key, String fallback) {
        Object v = getNode().tryGetContext(key);
        return v == null || v.toString().isEmpty() ? fallback : v.toString();
    }
}
