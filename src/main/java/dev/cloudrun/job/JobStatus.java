package dev.cloudrun.job;

import java.util.Map;
import java.util.Set;

public enum JobStatus {
    PENDING, QUEUED, PROVISIONING, RUNNING, COLLECTING_RESULTS, SUCCEEDED, FAILED, TIMED_OUT;

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = Map.of(
            // PENDING -> PROVISIONING: the poller can win the race against markQueued
            PENDING, Set.of(QUEUED, PROVISIONING),
            QUEUED, Set.of(PROVISIONING),
            PROVISIONING, Set.of(RUNNING, QUEUED, FAILED),
            RUNNING, Set.of(COLLECTING_RESULTS, TIMED_OUT, FAILED),
            COLLECTING_RESULTS, Set.of(SUCCEEDED, FAILED, TIMED_OUT)
    );

    public boolean canTransitionTo(JobStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }


    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }
}
