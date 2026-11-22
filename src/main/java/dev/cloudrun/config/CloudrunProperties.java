package dev.cloudrun.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudrun")
public record CloudrunProperties(Aws aws, Worker worker, Limits limits) {

    public record Aws(
            String endpointOverride,
            String region,
            String bucket,
            String queueName,
            String dlqName,
            String amiId,
            String instanceType,
            String instanceProfile,
            String instanceS3Endpoint
    ) {
    }

    public record Worker(
            boolean enabled,
            String compute,
            int maxConcurrentJobs,
            int pollWaitSeconds,
            int provisioningTimeoutSeconds,
            int ssmPollIntervalMillis,
            Fake fake
    ) {
    }

    public record Fake(
            int instanceDelayMillis,
            int runDelayMillis,
            int exitCode
    ) {
    }

    public record Limits(
            int maxScriptBytes,
            int maxTimeoutSeconds,
            int maxInlineOutputBytes
    ) {
    }
}
