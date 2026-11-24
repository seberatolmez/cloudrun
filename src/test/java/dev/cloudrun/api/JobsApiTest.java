package dev.cloudrun.api;

import dev.cloudrun.testsupport.Floci;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full async flow against the compose stack (Floci + app-managed Postgres testcontainer).
 * Skips when Floci is not reachable: start it with `make up` to run these tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class JobsApiTest {

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void fakeAdapterTiming(DynamicPropertyRegistry registry) {
        registry.add("cloudrun.worker.compute", () -> "fake");
        registry.add("cloudrun.worker.fake.run-delay-millis", () -> 200);
        registry.add("cloudrun.worker.fake.instance-delay-millis", () -> 100);
        registry.add("cloudrun.worker.poll-wait-seconds", () -> 1);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    dev.cloudrun.port.JobQueue jobQueue;

    @BeforeAll
    static void assumeFlociRunning() {
        Floci.assumeRunning();
    }

    @Test
    void happyPathReachesSucceeded() {
        var created = createJob("happy-path", "print('hello')");
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        assertThat(created.getBody().status()).isEqualTo("QUEUED");

        UUID jobId = created.getBody().id();
        var job = awaitTerminal(jobId, 30);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");

        var result = http.getForEntity("/jobs/" + jobId + "/result", JobResultView.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(result.getBody().exitCode()).isZero();
        assertThat(result.getBody().artifactPrefix()).isEqualTo("s3://cloudrun-artifacts/jobs/" + jobId + "/");
    }

    @Test
    void duplicateQueueMessageRunsJobOnce() {
        var created = createJob("duplicate-message", "print('once')");
        UUID jobId = created.getBody().id();

        // Simulate an SQS redelivery of the same job message
        jobQueue.send(jobId);

        awaitTerminal(jobId, 30);

        var jobView = http.getForEntity("/jobs/" + jobId, JobView.class).getBody();
        assertThat(jobView).isNotNull();
        assertThat(jobView.attemptCount()).isEqualTo(1);
    }

    @Test
    void invalidRequestsReturn400ProblemDetail() {
        assert400("runtime_not_python", Map.of(
                "name", "bad-runtime", "runtime", "node", "script", "print(1)", "timeoutSeconds", 120));
        assert400("blank_script", Map.of(
                "name", "blank", "runtime", "python", "script", "", "timeoutSeconds", 120));
        assert400("timeout_too_small", Map.of(
                "name", "tiny", "runtime", "python", "script", "print(1)", "timeoutSeconds", 0));
        assert400("script_too_large", Map.of(
                "name", "big", "runtime", "python", "script", "x".repeat(70_000), "timeoutSeconds", 120));
    }

    @Test
    void omittedTimeoutDefaultsAndIsAccepted() {
        var response = post(Map.of("name", "no-timeout", "runtime", "python", "script", "print(1)"),
                CreateJobResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void malformedJobIdReturns400NotServerError() {
        var response = http.getForEntity("/jobs/not-a-uuid", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void unknownRouteReturns404NotServerError() {
        var response = http.getForEntity("/no-such-route", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void unknownJobReturns404ProblemDetail() {
        var response = http.getForEntity("/jobs/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    private void assert400(String name, Map<String, Object> payload) {
        var response = post(payload);
        assertThat(response.getStatusCode().value()).as(name).isEqualTo(400);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    private ResponseEntity<CreateJobResponse> createJob(String name, String script) {
        var created = post(Map.of(
                "name", name,
                "runtime", "python",
                "script", script,
                "timeoutSeconds", 120), CreateJobResponse.class);
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        assertThat(created.getBody()).isNotNull();
        return created;
    }

    private <T> ResponseEntity<T> post(Map<String, Object> payload, Class<T> type) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/jobs", new HttpEntity<>(payload, headers), type);
    }

    private ResponseEntity<String> post(Map<String, Object> payload) {
        return post(payload, String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitTerminal(UUID jobId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            var response = http.getForEntity("/jobs/" + jobId, Map.class);
            Map<String, Object> body = response.getBody();
            String status = body == null ? "" : (String) body.get("status");
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status)) {
                return body;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Job " + jobId + " did not reach a terminal state in " + timeoutSeconds + "s");
    }
}
