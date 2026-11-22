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
 * API behavior with the worker disabled: jobs stay QUEUED, result returns 409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class JobsApiWorkerDisabledTest {

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void workerOff(DynamicPropertyRegistry registry) {
        registry.add("cloudrun.worker.enabled", () -> false);
    }

    @Autowired
    TestRestTemplate http;

    @BeforeAll
    static void assumeFlociRunning() {
        Floci.assumeRunning();
    }

    @Test
    void resultBeforeTerminalReturns409() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = http.postForEntity("/jobs",
                new HttpEntity<>(Map.of("name", "not-finished", "runtime", "python",
                        "script", "print(1)", "timeoutSeconds", 120), headers), CreateJobResponse.class);
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        UUID jobId = created.getBody().id();

        var view = http.getForEntity("/jobs/" + jobId, JobView.class).getBody();
        assertThat(view).isNotNull();
        assertThat(view.status().name()).isEqualTo("QUEUED");

        var result = http.getForEntity("/jobs/" + jobId + "/result", String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getHeaders().getContentType().toString()).contains("application/problem+json");
    }
}
