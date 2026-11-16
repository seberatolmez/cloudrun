package dev.cloudrun.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudrun")
public record CloudrunProperties(Aws aws, Worker worker, Limits limits) {

    public record Aws(
            String endpointOverride,
            String region,
            String bucket,
            String queueName,
            String dlqName
    ) {
    }

    public record Worker(
            boolean enabled,
            int maxConcurrentJobs,
            int pollWaitSeconds,
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
