package dev.cloudrun.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcClient jdbc;
    private JobRepository repo;

    private static Job newJob(UUID id) {
        return new Job(id, "test-job", "python", 120, "jobs/" + id + "/script.py",
                JobStatus.PENDING, InstanceState.NONE, null, null, 0,
                null, null, null, null, null, null, null);
    }

    @BeforeAll
    void setUp() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        repo = new JobRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test
    void claimIsIdempotent() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        assertThat(repo.markQueued(id)).isTrue();
        assertThat(repo.claim(id)).isTrue();
        // Second claim of the same job must not succeed (redelivered message / second worker)
        assertThat(repo.claim(id)).isFalse();
        assertThat(repo.claim(id)).isFalse();

        Job job = repo.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PROVISIONING);
        assertThat(job.attemptCount()).isEqualTo(1);
        assertThat(job.startedAt()).isNotNull();
    }

    @Test
    void claimWinsRaceAgainstMarkQueued() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        // Poller received the message before the API committed PENDING -> QUEUED
        assertThat(repo.claim(id)).isTrue();
        assertThat(repo.markQueued(id)).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(JobStatus.PROVISIONING);
        assertThat(repo.claim(id)).isFalse();
    }

    @Test
    void claimOfUnknownJobIsFalse() {
        assertThat(repo.claim(UUID.randomUUID())).isFalse();
    }

    @Test
    void markQueuedOnlyFromPending() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        assertThat(repo.markQueued(id)).isTrue();
        assertThat(repo.markQueued(id)).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void invalidTransitionIsRejected() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        // PENDING -> SUCCEEDED skips the whole pipeline; must not be possible
        assertThat(repo.transition(id, JobStatus.PENDING, JobStatus.SUCCEEDED)).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(JobStatus.PENDING);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    void finishRequiresCollectingResults() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        repo.markQueued(id);
        repo.claim(id);
        // Still PROVISIONING: finish out of COLLECTING_RESULTS must fail
        assertThat(repo.finish(id, JobStatus.SUCCEEDED, 0, null, null, null)).isFalse();

        assertThat(repo.transition(id, JobStatus.PROVISIONING, JobStatus.RUNNING)).isTrue();
        assertThat(repo.transition(id, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS)).isTrue();
        assertThat(repo.finish(id, JobStatus.SUCCEEDED, 0, null, null, "s3://bucket/jobs/" + id + "/")).isTrue();

        Job job = repo.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.exitCode()).isZero();
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.resultPrefix()).isEqualTo("s3://bucket/jobs/" + id + "/");
    }

    @Test
    void failWorksFromAnyActiveState() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        repo.markQueued(id);
        repo.claim(id);
        assertThat(repo.fail(id, "INTERNAL", "boom")).isTrue();
        Job job = repo.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("INTERNAL");
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void failDoesNothingOnTerminalState() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        repo.markQueued(id);
        repo.claim(id);
        repo.transition(id, JobStatus.PROVISIONING, JobStatus.RUNNING);
        repo.transition(id, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS);
        repo.finish(id, JobStatus.SUCCEEDED, 0, null, null, null);
        // SUCCEEDED is terminal: a late failure must not overwrite the outcome
        assertThat(repo.fail(id, "INTERNAL", "late error")).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void instanceLifecycleIsTrackedSeparately() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        repo.markQueued(id);
        repo.claim(id);
        assertThat(repo.instanceReady(id, "i-123")).isTrue();

        Job job = repo.find(id).orElseThrow();
        assertThat(job.instanceId()).isEqualTo("i-123");
        assertThat(job.instanceState()).isEqualTo(InstanceState.RUNNING);

        assertThat(repo.instanceTerminated(id, InstanceState.TERMINATED)).isTrue();
        assertThat(repo.find(id).orElseThrow().instanceState()).isEqualTo(InstanceState.TERMINATED);
    }

    @Test
    void everySuccessfulTransitionWritesAnEvent() {
        UUID id = UUID.randomUUID();
        repo.insert(newJob(id));
        repo.markQueued(id);
        repo.claim(id);
        repo.transition(id, JobStatus.PROVISIONING, JobStatus.RUNNING);
        repo.transition(id, JobStatus.RUNNING, JobStatus.COLLECTING_RESULTS);
        repo.finish(id, JobStatus.SUCCEEDED, 0, null, null, null);
        assertThat(eventCount(id)).isEqualTo(5);
    }

    private long eventCount(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = :id")
                .param("id", jobId)
                .query(Long.class)
                .single();
    }
}
