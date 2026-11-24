package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;

@Configuration
public class AwsClientsConfig {

    @Bean
    public S3Client s3Client(CloudrunProperties props) {
        var aws = props.aws();
        var builder = S3Client.builder().region(Region.of(aws.region()));
        if (isLocal(aws.endpointOverride())) {
            // Floci serves unsigned path-style requests (M1 findings)
            builder.endpointOverride(URI.create(aws.endpointOverride()))
                    .forcePathStyle(true)
                    .credentialsProvider(AnonymousCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(CloudrunProperties props) {
        return configure(SqsClient.builder(), props.aws()).build();
    }

    @Bean
    public Ec2Client ec2Client(CloudrunProperties props) {
        return configure(Ec2Client.builder(), props.aws()).build();
    }

    @Bean
    public SsmClient ssmClient(CloudrunProperties props) {
        return configure(SsmClient.builder(), props.aws()).build();
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, CloudrunProperties.Aws aws) {
        builder.region(Region.of(aws.region()));
        if (isLocal(aws.endpointOverride())) {
            // SDK refuses empty credentials; Floci accepts anything
            builder.endpointOverride(URI.create(aws.endpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder;
    }

    private static boolean isLocal(String endpointOverride) {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}
