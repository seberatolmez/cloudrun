package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

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
        var aws = props.aws();
        var builder = SqsClient.builder().region(Region.of(aws.region()));
        if (isLocal(aws.endpointOverride())) {
            // SDK refuses empty credentials; Floci accepts anything
            builder.endpointOverride(URI.create(aws.endpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }

    private static boolean isLocal(String endpointOverride) {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}
