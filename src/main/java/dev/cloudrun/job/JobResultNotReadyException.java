package dev.cloudrun.job;

import java.util.UUID;

public class JobResultNotReadyException extends RuntimeException {

    public JobResultNotReadyException(UUID jobId) {
        super("Job " + jobId + " has not finished yet");
    }
}
