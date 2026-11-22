package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.job.InstanceState;
import dev.cloudrun.job.Job;
import dev.cloudrun.job.JobRepository;
import dev.cloudrun.job.JobStatus;
import dev.cloudrun.port.InstanceProvisioner;
import dev.cloudrun.port.RemoteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs one already-claimed job: provision, execute, collect, finish.
 * Terminate runs in finally so a failed script or crash path never leaks an instance.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "enabled", havingValue = "true")
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository repo;
    private final InstanceProvisioner provisioner;
    private final RemoteExecutor executor;
    private final String bucket;
    private final Duration provisioningTimeout;

    public JobExecutor(JobRepository repo, InstanceProvisioner provisioner,
                       RemoteExecutor executor, CloudrunProperties props) {
        this.repo = repo;
        this.provisioner = provisioner;
        this.executor = executor;
        this.bucket = props.aws().bucket();
        this.provisioningTimeout = Duration.ofSeconds(props.worker().provisioningTimeoutSeconds());
    }

    public void process(UUID jobId) {
        MDC.put("jobId", jobId.toString());
        try {
            doProcess(jobId);
        } finally {
            MDC.remove("jobId");
        }
    }

    private void doProcess(UUID jobId) {
        Job job = repo.find(jobId).orElse(null);
        if (job == null) {
            log.error("Claimed job {} not found; skipping", jobId);
            return;
        }
        String instanceId = null;
        try {
            String launched = step("PROVISIONING_FAILED", () -> provisioner.provision(jobId, job.attemptCount()));
            instanceId = launched;
            repo.instanceReady(jobId, launched);
            step("SSM_UNAVAILABLE", () -> executor.awaitReady(launched, provisioningTimeout));

            String commandId = step("SSM_UNAVAILABLE",
                    () -> executor.start(launched, job.scriptKey(), job.timeoutSeconds()));
            repo.markRunning(jobId, commandId);

            Instant deadline = Instant.now().plusSeconds(job.timeoutSeconds());
            var result = step("SSM_UNAVAILABLE", () -> executor.await(launched, commandId, deadline));

            repo.transition(jobId, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS);
            finish(job, result);
        } catch (StepFailedException e) {
            log.error("Job failed during {}", e.reason, e.getCause());
            repo.fail(jobId, e.reason, e.getCause().toString());
        } catch (Exception e) {
            log.error("Job execution failed", e);
            repo.fail(jobId, "INTERNAL", e.toString());
        } finally {
            if (instanceId != null) {
                terminateQuietly(jobId, instanceId);
            }
        }
    }

    private static <T> T step(String failureReason, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new StepFailedException(failureReason, e);
        }
    }

    private static void step(String failureReason, Runnable call) {
        step(failureReason, () -> {
            call.run();
            return null;
        });
    }

    private static final class StepFailedException extends RuntimeException {
        final String reason;

        StepFailedException(String reason, RuntimeException cause) {
            super(cause);
            this.reason = reason;
        }
    }

    private void finish(Job job, RemoteExecutor.CommandResult result) {
        UUID id = job.id();
        String prefix = resultPrefix(id);
        switch (result.status()) {
            case SUCCESS -> {
                repo.finish(id, JobStatus.SUCCEEDED, 0, null, null, prefix);
                log.info("Job succeeded");
            }
            case FAILED -> {
                repo.finish(id, JobStatus.FAILED, result.exitCode(), "SCRIPT_ERROR", result.stderr(), prefix);
                log.info("Job failed with exit code {}", result.exitCode());
            }
            case TIMED_OUT -> {
                repo.finish(id, JobStatus.TIMED_OUT, null, "TIMEOUT",
                        "script exceeded " + job.timeoutSeconds() + " seconds", prefix);
                log.info("Job timed out");
            }
            case CANCELLED -> {
                repo.finish(id, JobStatus.FAILED, null, "INTERNAL", "command was cancelled externally", prefix);
                log.warn("Command was cancelled externally");
            }
        }
    }

    private void terminateQuietly(UUID jobId, String instanceId) {
        try {
            provisioner.terminate(instanceId);
            repo.instanceTerminated(jobId, InstanceState.TERMINATED);
        } catch (Exception e) {
            log.error("Terminate failed for {}", instanceId, e);
            repo.instanceTerminated(jobId, InstanceState.TERMINATE_FAILED);
        }
    }

    private String resultPrefix(UUID jobId) {
        return "s3://" + bucket + "/jobs/" + jobId + "/";
    }
}
