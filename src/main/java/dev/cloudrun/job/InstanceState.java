package dev.cloudrun.job;

/**
 * Lifecycle of the managed EC2 instance, tracked separately from jobs.status:
 * a job can fail while its terminate still succeeds (or vice versa).
 */
public enum InstanceState {
    NONE, RUNNING, TERMINATING, TERMINATED, TERMINATE_FAILED;

    public boolean isDirty() {
        return this != NONE && this != TERMINATED;
    }
}
