package dev.cloudrun.port;

import java.time.Duration;
import java.time.Instant;

/** Port for executing a job script on a provisioned instance. */
public interface RemoteExecutor {

    /** Blocks until the instance accepts commands; throws when it does not within the timeout. */
    void awaitReady(String instanceId, Duration timeout);

    /** Starts the script and returns the command id without waiting for it to finish. */
    String start(String instanceId, String scriptKey, int timeoutSeconds);

    /** Waits for the command to finish; cancels it and reports TIMED_OUT when the deadline passes first. */
    CommandResult await(String instanceId, String commandId, Instant deadline);

    enum Status { SUCCESS, FAILED, TIMED_OUT, CANCELLED }

    record CommandResult(Status status, int exitCode, String stdout, String stderr) {
    }
}
