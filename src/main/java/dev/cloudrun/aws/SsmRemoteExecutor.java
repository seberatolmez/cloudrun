package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.RemoteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CancelCommandRequest;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "compute", havingValue = "aws")
public class SsmRemoteExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(SsmRemoteExecutor.class);
    private static final String DOCUMENT = "AWS-RunShellScript";
    // In-instance timeout outlives the orchestrator deadline so the deadline path reports TIMED_OUT
    private static final int INSTANCE_TIMEOUT_GRACE_SECONDS = 10;
    private static final int COMMAND_TIMEOUT_MARGIN_SECONDS = 30;

    private final SsmClient ssm;
    private final String bucket;
    private final String instanceS3Endpoint;
    private final long pollIntervalMillis;

    public SsmRemoteExecutor(SsmClient ssm, CloudrunProperties props) {
        this.ssm = ssm;
        this.bucket = props.aws().bucket();
        this.instanceS3Endpoint = props.aws().instanceS3Endpoint();
        this.pollIntervalMillis = props.worker().ssmPollIntervalMillis();
    }

    @Override
    public void awaitReady(String instanceId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                String commandId = send(instanceId, "echo ready", COMMAND_TIMEOUT_MARGIN_SECONDS);
                if (await(instanceId, commandId, deadline).status() == Status.SUCCESS) {
                    return;
                }
            } catch (SsmException e) {
                log.debug("Instance {} not ready for SSM yet: {}", instanceId, e.getMessage());
            }
            sleep();
        }
        throw new IllegalStateException("Instance " + instanceId + " did not accept SSM commands within " + timeout);
    }

    @Override
    public String start(String instanceId, String scriptKey, int timeoutSeconds) {
        int instanceTimeout = timeoutSeconds + INSTANCE_TIMEOUT_GRACE_SECONDS;
        String command = "curl -sf '%s/%s/%s' -o /tmp/script.py && timeout %d python3 /tmp/script.py"
                .formatted(instanceS3Endpoint, bucket, scriptKey, instanceTimeout);
        return send(instanceId, command, instanceTimeout + COMMAND_TIMEOUT_MARGIN_SECONDS);
    }

    @Override
    public CommandResult await(String instanceId, String commandId, Instant deadline) {
        while (true) {
            var invocation = invocation(instanceId, commandId);
            if (invocation != null) {
                CommandResult result = toResult(invocation.status(), invocation.responseCode(),
                        invocation.standardOutputContent(), invocation.standardErrorContent());
                if (result != null) {
                    return result;
                }
            }
            if (!Instant.now().isBefore(deadline)) {
                return cancel(instanceId, commandId);
            }
            sleep();
        }
    }

    /** Maps an SSM invocation to a result, or null while the command is still running. */
    static CommandResult toResult(CommandInvocationStatus status, Integer responseCode, String stdout, String stderr) {
        int exitCode = responseCode == null ? -1 : responseCode;
        return switch (status) {
            case PENDING, IN_PROGRESS, DELAYED, CANCELLING -> null;
            case SUCCESS -> new CommandResult(Status.SUCCESS, 0, stdout, stderr);
            case FAILED -> new CommandResult(Status.FAILED, exitCode, stdout, stderr);
            case TIMED_OUT -> new CommandResult(Status.TIMED_OUT, exitCode, stdout, stderr);
            case CANCELLED -> new CommandResult(Status.CANCELLED, exitCode, stdout, stderr);
            default -> throw new IllegalStateException("Unexpected SSM command status: " + status);
        };
    }

    private String send(String instanceId, String command, int executionTimeoutSeconds) {
        return ssm.sendCommand(SendCommandRequest.builder()
                        .instanceIds(instanceId)
                        .documentName(DOCUMENT)
                        .parameters(Map.of(
                                "commands", List.of(command),
                                "executionTimeout", List.of(String.valueOf(executionTimeoutSeconds))))
                        .build())
                .command().commandId();
    }

    private GetCommandInvocationResponse invocation(String instanceId, String commandId) {
        try {
            return ssm.getCommandInvocation(GetCommandInvocationRequest.builder()
                    .commandId(commandId).instanceId(instanceId).build());
        } catch (InvocationDoesNotExistException e) {
            // Right after SendCommand the invocation may not be visible yet
            return null;
        }
    }

    private CommandResult cancel(String instanceId, String commandId) {
        try {
            ssm.cancelCommand(CancelCommandRequest.builder().commandId(commandId).instanceIds(instanceId).build());
        } catch (SsmException e) {
            log.warn("Cancel of command {} failed: {}", commandId, e.getMessage());
        }
        String stdout = "";
        String stderr = "";
        try {
            var invocation = invocation(instanceId, commandId);
            if (invocation != null) {
                stdout = invocation.standardOutputContent();
                stderr = invocation.standardErrorContent();
            }
        } catch (SsmException e) {
            log.warn("Could not read partial output of command {}: {}", commandId, e.getMessage());
        }
        return new CommandResult(Status.TIMED_OUT, -1, stdout, stderr);
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SSM", e);
        }
    }
}
