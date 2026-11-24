package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.RemoteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Stand-in for SsmRemoteExecutor: sleeps for the configured delay and exits
 * with the configured code. exit-code != 0 exercises the FAILED(SCRIPT_ERROR) path.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "compute", havingValue = "fake")
public class FakeRemoteExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(FakeRemoteExecutor.class);

    private final int delayMillis;
    private final int exitCode;

    public FakeRemoteExecutor(CloudrunProperties props) {
        this.delayMillis = props.worker().fake().runDelayMillis();
        this.exitCode = props.worker().fake().exitCode();
    }

    @Override
    public void awaitReady(String instanceId, Duration timeout) {
    }

    @Override
    public String start(String instanceId, String scriptKey, int timeoutSeconds) {
        log.info("Executing script {} (timeout {}s, exit code {})", scriptKey, timeoutSeconds, exitCode);
        return "fake-cmd-" + UUID.randomUUID();
    }

    @Override
    public CommandResult await(String instanceId, String commandId, Instant deadline) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (exitCode == 0) {
            return new CommandResult(Status.SUCCESS, 0, "", "");
        }
        return new CommandResult(Status.FAILED, exitCode, "", "fake failure (configured exit code)");
    }
}
