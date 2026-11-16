package dev.cloudrun.job;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.ArtifactStore;
import dev.cloudrun.port.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Accepts a job: validate, upload script, insert PENDING, enqueue, then mark QUEUED.
 * A crash between insert and enqueue leaves a PENDING row that the M5 Reconciler requeues.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    public static final String RUNTIME = "python";

    private final JobRepository repo;
    private final ArtifactStore artifactStore;
    private final JobQueue jobQueue;
    private final CloudrunProperties props;

    public JobService(JobRepository repo, ArtifactStore artifactStore, JobQueue jobQueue, CloudrunProperties props) {
        this.repo = repo;
        this.artifactStore = artifactStore;
        this.jobQueue = jobQueue;
        this.props = props;
    }

    public record CreateResult(UUID id, JobStatus status) {
    }

    public CreateResult create(String name, String runtime, String script, Integer timeoutSeconds) {
        validate(runtime, script, timeoutSeconds);
        int timeout = timeoutSeconds == null ? 120 : timeoutSeconds;

        UUID id = UUID.randomUUID();
        artifactStore.uploadScript(id, script);

        var job = new Job(id, name, runtime, timeout, artifactStore.scriptKey(id),
                JobStatus.PENDING, InstanceState.NONE, null, null, 0,
                null, null, null, null, null, null, null);
        repo.insert(job);

        // QUEUED must be committed before the message goes out, otherwise the poller
        // can receive it while the row is still PENDING, fail the conditional claim,
        // and delete it. If send fails the job sits QUEUED until the M5 Reconciler requeues it.
        if (!repo.markQueued(id)) {
            log.warn("Job {} could not move PENDING->QUEUED; stays PENDING (M5 Reconciler will requeue)", id);
        }
        jobQueue.send(id);
        log.info("Job {} accepted (timeout {}s)", id, timeout);
        return new CreateResult(id, repo.find(id).orElseThrow().status());
    }

    public Job get(UUID id) {
        return repo.find(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public Job result(UUID id) {
        Job job = get(id);
        if (!job.status().isTerminal()) {
            throw new JobResultNotReadyException(id);
        }
        return job;
    }

    private void validate(String runtime, String script, Integer timeoutSeconds) {
        if (!RUNTIME.equals(runtime)) {
            throw new InvalidJobException("runtime must be \"" + RUNTIME + "\"");
        }
        int scriptBytes = script.getBytes(StandardCharsets.UTF_8).length;
        if (scriptBytes > props.limits().maxScriptBytes()) {
            throw new InvalidJobException("script exceeds the maximum of " + props.limits().maxScriptBytes() + " bytes");
        }
        if (timeoutSeconds != null && timeoutSeconds > props.limits().maxTimeoutSeconds()) {
            throw new InvalidJobException("timeoutSeconds exceeds the maximum of " + props.limits().maxTimeoutSeconds());
        }
    }
}
