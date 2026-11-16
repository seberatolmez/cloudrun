package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
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

    public JobExecutor(JobRepository repo, InstanceProvisioner provisioner,
                       RemoteExecutor executor, CloudrunProperties props) {
        this.repo = repo;
        this.provisioner = provisioner;
        this.executor = executor;
        this.bucket = props.aws().bucket();
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
            instanceId = provisioner.provision(jobId);
            repo.instanceReady(jobId, instanceId);
            repo.transition(jobId, JobStatus.PROVISIONING, JobStatus.RUNNING);

            var result = executor.run(jobId.toString(), job.scriptKey(), job.timeoutSeconds());

            repo.transition(jobId, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS);
            if (result.exitCode() == 0) {
                repo.finish(jobId, JobStatus.SUCCEEDED, 0, null, null, resultPrefix(jobId));
                log.info("Job succeeded");
            } else {
                // Script failure is a user error: no retry
                repo.finish(jobId, JobStatus.FAILED, result.exitCode(), "SCRIPT_ERROR",
                        result.stderr(), resultPrefix(jobId));
                log.info("Job failed with exit code {}", result.exitCode());
            }
        } catch (Exception e) {
            log.error("Job execution failed", e);
            repo.fail(jobId, "INTERNAL", e.toString());
        } finally {
            if (instanceId != null) {
                terminateQuietly(jobId, instanceId);
            }
        }
    }

    private void terminateQuietly(UUID jobId, String instanceId) {
        try {
            provisioner.terminate(instanceId);
            repo.instanceTerminated(jobId, dev.cloudrun.job.InstanceState.TERMINATED);
        } catch (Exception e) {
            log.error("Terminate failed for {}", instanceId, e);
            repo.instanceTerminated(jobId, dev.cloudrun.job.InstanceState.TERMINATE_FAILED);
        }
    }

    private String resultPrefix(UUID jobId) {
        return "s3://" + bucket + "/jobs/" + jobId + "/";
    }
}
