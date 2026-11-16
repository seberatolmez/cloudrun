package dev.cloudrun.port;

/** Port for executing a job script on the provisioned instance. */
public interface RemoteExecutor {

    ExecutionResult run(String jobId, String scriptKey, int timeoutSeconds);

    record ExecutionResult(int exitCode, String stderr) {
    }
}
