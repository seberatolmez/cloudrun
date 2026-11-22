package dev.cloudrun.orchestrator;

import dev.cloudrun.job.InstanceState;
import dev.cloudrun.job.Job;
import dev.cloudrun.job.JobRepository;
import dev.cloudrun.job.JobStatus;
import dev.cloudrun.port.InstanceProvisioner;
import dev.cloudrun.port.RemoteExecutor;
import dev.cloudrun.port.RemoteExecutor.CommandResult;
import dev.cloudrun.port.RemoteExecutor.Status;
import dev.cloudrun.testsupport.Floci;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobExecutorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JobRepository repo;
    private StubProvisioner provisioner;
    private StubExecutor remote;
    private JobExecutor jobExecutor;

    @BeforeAll
    void setUpDatabase() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        repo = new JobRepository(JdbcClient.create(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @BeforeEach
    void freshStubs() {
        provisioner = new StubProvisioner();
        remote = new StubExecutor();
        jobExecutor = new JobExecutor(repo, provisioner, remote, Floci.props());
    }

    @Test
    void successfulRunRecordsCommandIdAndTerminatesInstance() {
        remote.result = new CommandResult(Status.SUCCESS, 0, "out", "");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.exitCode()).isZero();
        assertThat(job.ssmCommandId()).isEqualTo("cmd-1");
        assertThat(job.instanceId()).isEqualTo("i-1");
        assertThat(job.instanceState()).isEqualTo(InstanceState.TERMINATED);
        assertThat(provisioner.terminated).containsExactly("i-1");
    }

    @Test
    void scriptFailureKeepsExitCodeAndStderrWithoutRetryReason() {
        remote.result = new CommandResult(Status.FAILED, 3, "", "boom");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("SCRIPT_ERROR");
        assertThat(job.exitCode()).isEqualTo(3);
        assertThat(job.failureDetail()).isEqualTo("boom");
        assertThat(provisioner.terminated).containsExactly("i-1");
    }

    @Test
    void deadlineExpiryEndsTimedOutAndTerminates() {
        remote.result = new CommandResult(Status.TIMED_OUT, -1, "", "");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.TIMED_OUT);
        assertThat(job.failureReason()).isEqualTo("TIMEOUT");
        assertThat(job.exitCode()).isNull();
        assertThat(provisioner.terminated).containsExactly("i-1");
    }

    @Test
    void externalCancelIsAnInternalFailureNotATimeout() {
        remote.result = new CommandResult(Status.CANCELLED, -1, "", "");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("INTERNAL");
    }

    @Test
    void provisioningFailureIsClassifiedAndLeavesNoInstance() {
        provisioner.provisionError = new IllegalStateException("capacity");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("PROVISIONING_FAILED");
        assertThat(job.instanceState()).isEqualTo(InstanceState.NONE);
        assertThat(provisioner.terminated).isEmpty();
    }

    @Test
    void instanceThatNeverAcceptsCommandsIsSsmUnavailableAndStillTerminated() {
        remote.readyError = new IllegalStateException("no ssm");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("SSM_UNAVAILABLE");
        assertThat(job.instanceState()).isEqualTo(InstanceState.TERMINATED);
        assertThat(provisioner.terminated).containsExactly("i-1");
    }

    @Test
    void failedTerminateIsRecordedWithoutChangingTheJobOutcome() {
        remote.result = new CommandResult(Status.SUCCESS, 0, "", "");
        provisioner.terminateError = new IllegalStateException("api down");

        Job job = process();

        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.instanceState()).isEqualTo(InstanceState.TERMINATE_FAILED);
    }

    private Job process() {
        UUID id = UUID.randomUUID();
        repo.insert(new Job(id, "t", "python", 60, "jobs/" + id + "/script.py", JobStatus.PENDING,
                InstanceState.NONE, null, null, 0, null, null, null, null, null, null, null));
        repo.markQueued(id);
        assertThat(repo.claim(id)).isTrue();
        jobExecutor.process(id);
        return repo.find(id).orElseThrow();
    }

    private static class StubProvisioner implements InstanceProvisioner {
        RuntimeException provisionError;
        RuntimeException terminateError;
        final List<String> terminated = new ArrayList<>();

        @Override
        public String provision(UUID jobId, int attempt) {
            if (provisionError != null) {
                throw provisionError;
            }
            return "i-1";
        }

        @Override
        public void terminate(String instanceId) {
            if (terminateError != null) {
                throw terminateError;
            }
            terminated.add(instanceId);
        }

        @Override
        public List<String> listManaged() {
            return List.of();
        }
    }

    private static class StubExecutor implements RemoteExecutor {
        RuntimeException readyError;
        CommandResult result;

        @Override
        public void awaitReady(String instanceId, Duration timeout) {
            if (readyError != null) {
                throw readyError;
            }
        }

        @Override
        public String start(String instanceId, String scriptKey, int timeoutSeconds) {
            return "cmd-1";
        }

        @Override
        public CommandResult await(String instanceId, String commandId, Instant deadline) {
            return result;
        }
    }
}
