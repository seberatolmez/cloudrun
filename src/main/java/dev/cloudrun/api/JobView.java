package dev.cloudrun.api;

import dev.cloudrun.job.Job;
import dev.cloudrun.job.InstanceState;
import dev.cloudrun.job.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobView(
        UUID id,
        String name,
        JobStatus status,
        InstanceState instanceState,
        int attemptCount,
        Integer exitCode,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    static JobView from(Job job) {
        return new JobView(job.id(), job.name(), job.status(), job.instanceState(),
                job.attemptCount(), job.exitCode(), job.failureReason(),
                job.createdAt(), job.startedAt(), job.finishedAt());
    }
}
