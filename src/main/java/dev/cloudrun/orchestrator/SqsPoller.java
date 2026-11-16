package dev.cloudrun.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.job.JobRepository;
import dev.cloudrun.port.JobQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Long-polls the queue and processes each message on its own virtual thread,
 * bounded by a semaphore. Claim happens before the message is deleted, so a
 * redelivered message can never process a job twice.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "enabled", havingValue = "true")
public class SqsPoller {

    private static final Logger log = LoggerFactory.getLogger(SqsPoller.class);

    private final JobQueue queue;
    private final JobRepository repo;
    private final JobExecutor jobExecutor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxConcurrentJobs;
    private final int pollWaitSeconds;

    private final Semaphore permits;
    private volatile boolean running;
    private ExecutorService workers;

    public SqsPoller(JobQueue queue, JobRepository repo, JobExecutor jobExecutor, CloudrunProperties props) {
        this.queue = queue;
        this.repo = repo;
        this.jobExecutor = jobExecutor;
        this.maxConcurrentJobs = props.worker().maxConcurrentJobs();
        this.pollWaitSeconds = props.worker().pollWaitSeconds();
        this.permits = new Semaphore(maxConcurrentJobs);
    }

    @PostConstruct
    void start() {
        running = true;
        workers = Executors.newVirtualThreadPerTaskExecutor();
        Thread.ofVirtual().name("sqs-poller").start(this::pollLoop);
        log.info("Poller started (maxConcurrentJobs={}, pollWaitSeconds={})", maxConcurrentJobs, pollWaitSeconds);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (workers != null) {
            workers.shutdown();
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                for (var message : queue.receive(maxConcurrentJobs, pollWaitSeconds)) {
                    permits.acquire();
                    workers.submit(() -> {
                        try {
                            process(message);
                        } finally {
                            permits.release();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Poll cycle failed: {}", e.toString());
            }
        }
    }

    void process(JobQueue.QueueMessage message) {
        UUID jobId;
        try {
            String raw = mapper.readTree(message.body()).path("jobId").asText(null);
            jobId = raw == null ? null : UUID.fromString(raw);
        } catch (Exception e) {
            // shortcut: poison payloads get deleted here; DLQ + DlqDrainer handling comes in M5
            log.warn("Unparseable message {} dropped: {}", message.messageId(), e.toString());
            queue.delete(message);
            return;
        }
        if (jobId == null) {
            log.warn("Message {} has no jobId; dropped", message.messageId());
            queue.delete(message);
            return;
        }
        try {
            if (repo.claim(jobId)) {
                // Claim won: only now is the message safe to delete
                queue.delete(message);
                jobExecutor.process(jobId);
            } else {
                // Already claimed or finished: redelivery, drop silently
                log.debug("Message for job {} not claimed; deleting", jobId);
                queue.delete(message);
            }
        } catch (DataAccessException e) {
            // DB unavailable: leave the message for redelivery (DLQ after 3 tries)
            log.error("DB error while claiming job {}; message left on queue", jobId, e);
        }
    }
}
