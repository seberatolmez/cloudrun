package dev.cloudrun.testsupport;

import dev.cloudrun.config.CloudrunProperties;
import org.junit.jupiter.api.Assumptions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.time.Duration;

/** Access to the compose Floci stack; tests are skipped when it is not running (`make up`). */
public final class Floci {

    public static final String ENDPOINT = "http://localhost:4566";

    private Floci() {
    }

    public static void assumeRunning() {
        try (SqsClient probe = SqsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(credentials())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)))
                .build()) {
            probe.listQueues();
        } catch (Exception e) {
            Assumptions.abort("Floci is not reachable on localhost:4566 - start it with `make up`");
        }
    }

    public static Ec2Client ec2() {
        return configure(Ec2Client.builder()).build();
    }

    public static SsmClient ssm() {
        return configure(SsmClient.builder()).build();
    }

    public static S3Client s3() {
        return configure(S3Client.builder()).forcePathStyle(true).build();
    }

    public static CloudrunProperties props() {
        return new CloudrunProperties(
                new CloudrunProperties.Aws(ENDPOINT, "us-east-1", "cloudrun-artifacts", "cloudrun-jobs",
                        "cloudrun-jobs-dlq", "ami-0abcdef1234567891", "t3.micro", "",
                        "http://host.docker.internal:4566"),
                new CloudrunProperties.Worker(true, "aws", 3, 1, 120, 500,
                        new CloudrunProperties.Fake(0, 0, 0)),
                new CloudrunProperties.Limits(65536, 900, 262144));
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder) {
        builder.region(Region.US_EAST_1)
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(credentials());
        return builder;
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }
}
