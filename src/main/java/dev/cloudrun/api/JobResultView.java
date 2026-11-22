package dev.cloudrun.api;

import dev.cloudrun.job.Job;

/**
 * In M2 stdout/stderr are placeholders; real artifacts are collected from S3 in M4.
 */
public record JobResultView(
        String status,
        Integer exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        String artifactPrefix
) {
    static JobResultView from(Job job) {
        return new JobResultView(job.status().name(), job.exitCode(),
                "", job.failureDetail() == null ? "" : job.failureDetail(),
                false, job.resultPrefix());
    }
}
