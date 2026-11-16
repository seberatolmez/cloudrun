package dev.cloudrun.job;

import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        String name,
        String runtime,
        int timeoutSeconds,
        String scriptKey,
        JobStatus status,
        InstanceState instanceState,
        String instanceId,
        String ssmCommandId,
        int attemptCount,
        Integer exitCode,
        String failureReason,
        String failureDetail,
        String resultPrefix,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
