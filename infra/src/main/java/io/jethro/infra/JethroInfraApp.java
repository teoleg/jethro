package io.jethro.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;

/**
 * CDK app entry point (ADR-0007/0013). Synthesises the single-node dev stack — the EC2
 * host, its IAM/networking, the ECR repo, the GitHub OIDC deploy role, cost controls, and
 * the non-secret SSM parameters — codifying the {@code deploy/README.md} runbook.
 *
 * <p>Account/region come from the standard {@code CDK_DEFAULT_ACCOUNT}/{@code CDK_DEFAULT_REGION}
 * (i.e. your {@code aws} CLI credentials). Deploy: {@code cd infra && cdk deploy}. The
 * production-shape stacks (Fargate/Aurora/ALB, ADR-0007) will live here behind a stage flag;
 * only the dev stage exists today.
 */
public final class JethroInfraApp {

    private JethroInfraApp() {
    }

    public static void main(String[] args) {
        App app = new App();

        Environment env = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build();

        new JethroDevStack(app, "JethroDev", StackProps.builder()
                .env(env)
                .description("Jethro single-node dev stack (ADR-0013): EC2 + ECR + OIDC deploy role")
                .build());

        // FinOps tagging (ADR-0011): everything in this app carries project/service.
        Tags.of(app).add("project", "jethro");
        Tags.of(app).add("service", "platform");
        Tags.of(app).add("stage", "dev");

        app.synth();
    }
}
