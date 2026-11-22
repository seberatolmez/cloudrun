package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.RemoteExecutor.CommandResult;
import dev.cloudrun.port.RemoteExecutor.Status;
import dev.cloudrun.testsupport.Floci;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SsmRemoteExecutorTest {

    private Ec2InstanceProvisioner provisioner;
    private SsmRemoteExecutor executor;
    private S3Client s3;
    private CloudrunProperties props;
    private String instanceId;

    @BeforeAll
    void provisionInstance() {
        Floci.assumeRunning();
        props = Floci.props();
        provisioner = new Ec2InstanceProvisioner(Floci.ec2(), props);
        executor = new SsmRemoteExecutor(Floci.ssm(), props);
        s3 = Floci.s3();
        ensureBucket();

        provisioner.listManaged().forEach(provisioner::terminate);
        instanceId = provisioner.provision(UUID.randomUUID(), 1);
        executor.awaitReady(instanceId, Duration.ofSeconds(120));
    }

    @AfterAll
    void cleanUp() {
        if (provisioner != null) {
            provisioner.listManaged().forEach(provisioner::terminate);
            assertThat(provisioner.listManaged()).isEmpty();
        }
    }

    @Test
    void successfulScriptReturnsStdoutAndExitCodeZero() {
        CommandResult result = run("print('hello from the instance')", 60, 60);

        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello from the instance");
    }

    @Test
    void failingScriptReturnsItsExitCodeAndStderr() {
        CommandResult result = run("import sys\nprint('boom', file=sys.stderr)\nsys.exit(3)", 60, 60);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("boom");
    }

    @Test
    void deadlineCancelsLongRunningScriptAndReportsTimedOut() {
        CommandResult result = run("import time\ntime.sleep(120)", 120, 3);

        assertThat(result.status()).isEqualTo(Status.TIMED_OUT);
    }

    private CommandResult run(String script, int scriptTimeoutSeconds, int deadlineSeconds) {
        String key = "jobs/" + UUID.randomUUID() + "/script.py";
        s3.putObject(b -> b.bucket(props.aws().bucket()).key(key), RequestBody.fromString(script));
        String commandId = executor.start(instanceId, key, scriptTimeoutSeconds);
        return executor.await(instanceId, commandId, Instant.now().plusSeconds(deadlineSeconds));
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.aws().bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(props.aws().bucket()));
        }
    }
}
