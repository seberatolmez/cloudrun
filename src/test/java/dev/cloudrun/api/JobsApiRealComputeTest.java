package dev.cloudrun.api;

import dev.cloudrun.aws.Ec2InstanceProvisioner;
import dev.cloudrun.testsupport.Floci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Full flow through the app onto real Floci EC2 + SSM; needs the compose stack (`make up`). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class JobsApiRealComputeTest {

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void realCompute(DynamicPropertyRegistry registry) {
        // Own queue: this context stays cached with a live poller and would otherwise steal other tests' messages
        registry.add("cloudrun.aws.queue-name", () -> "cloudrun-jobs-real-compute");
        registry.add("cloudrun.aws.dlq-name", () -> "cloudrun-jobs-real-compute-dlq");
        registry.add("cloudrun.worker.compute", () -> "aws");
        registry.add("cloudrun.worker.poll-wait-seconds", () -> 1);
        registry.add("cloudrun.worker.ssm-poll-interval-millis", () -> 500);
    }

    @Autowired
    TestRestTemplate http;

    private Ec2InstanceProvisioner ec2;

    @BeforeAll
    static void assumeFloci() {
        Floci.assumeRunning();
    }

    @BeforeEach
    @AfterEach
    void noManagedInstancesRemain() {
        ec2 = new Ec2InstanceProvisioner(Floci.ec2(), Floci.props());
        assertThat(ec2.listManaged()).as("managed instances left running").isEmpty();
    }

    @Test
    void pythonScriptRunsOnRealInstanceAndSucceeds() {
        UUID id = submit("print('hello from a real instance')", 60);

        var job = awaitJob(id, j -> "SUCCEEDED".equals(j.get("status")), 240);

        assertThat(job.get("exitCode")).isEqualTo(0);
        awaitJob(id, j -> "TERMINATED".equals(j.get("instanceState")), 90);
    }

    @Test
    void failingScriptEndsFailedWithScriptErrorAndReadableStderr() {
        UUID id = submit("import sys\nprint('boom', file=sys.stderr)\nsys.exit(1)", 60);

        var job = awaitJob(id, j -> "FAILED".equals(j.get("status")), 240);

        assertThat(job.get("failureReason")).isEqualTo("SCRIPT_ERROR");
        assertThat(job.get("exitCode")).isEqualTo(1);
        var result = http.getForEntity("/jobs/" + id + "/result", Map.class).getBody();
        assertThat((String) result.get("stderr")).contains("boom");
        awaitJob(id, j -> "TERMINATED".equals(j.get("instanceState")), 90);
    }

    @Test
    void scriptExceedingItsTimeoutEndsTimedOutAndInstanceIsTerminated() {
        UUID id = submit("import time\ntime.sleep(300)", 5);

        var job = awaitJob(id, j -> "TIMED_OUT".equals(j.get("status")), 240);

        assertThat(job.get("failureReason")).isEqualTo("TIMEOUT");
        awaitJob(id, j -> "TERMINATED".equals(j.get("instanceState")), 90);
    }

    private UUID submit(String script, int timeoutSeconds) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity("/jobs", new HttpEntity<>(Map.of(
                "name", "real-compute", "runtime", "python",
                "script", script, "timeoutSeconds", timeoutSeconds), headers), CreateJobResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return response.getBody().id();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitJob(UUID id, Predicate<Map<String, Object>> condition, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = http.getForEntity("/jobs/" + id, Map.class).getBody();
            if (last != null && condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Job " + id + " did not reach the expected state in " + timeoutSeconds + "s, last: " + last);
    }
}
