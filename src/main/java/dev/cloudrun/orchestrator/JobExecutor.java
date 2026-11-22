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
            instanceId = provisioner.provision(jobId, job.attemptCount());
            repo.instanceReady(jobId, instanceId);
            executor.awaitReady(instanceId, provisioningTimeout);

            String commandId = executor.start(instanceId, job.scriptKey(), job.timeoutSeconds());
            repo.transition(jobId, JobStatus.PROVISIONING, JobStatus.RUNNING);

            Instant deadline = Instant.now().plusSeconds(job.timeoutSeconds());
            var result = executor.await(instanceId, commandId, deadline);

            repo.transition(jobId, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS);
            finish(job, result);
        } catch (Exception e) {
            log.error("Job execution failed", e);
            repo.fail(jobId, "INTERNAL", e.toString());
        } finally {
            if (instanceId != null) {
                terminateQuietly(jobId, instanceId);
            }
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
