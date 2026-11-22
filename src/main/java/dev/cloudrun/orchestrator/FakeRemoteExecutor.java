package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.RemoteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M2 stand-in for SsmRemoteExecutor (M3): sleeps for the configured delay
 * and exits with the configured code. exit-code != 0 exercises the
 * FAILED(SCRIPT_ERROR) path.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "enabled", havingValue = "true")
public class FakeRemoteExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(FakeRemoteExecutor.class);

    private final int delayMillis;
    private final int exitCode;

    public FakeRemoteExecutor(CloudrunProperties props) {
        this.delayMillis = props.worker().fake().runDelayMillis();
        this.exitCode = props.worker().fake().exitCode();
    }

    @Override
    public ExecutionResult run(String jobId, String scriptKey, int timeoutSeconds) {
        log.info("Executing script {} (timeout {}s, exit code {})", scriptKey, timeoutSeconds, exitCode);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ExecutionResult(exitCode, exitCode == 0 ? "" : "fake failure (configured exit code)");
    }
}
