package dev.cloudrun.aws;

import dev.cloudrun.port.RemoteExecutor.CommandResult;
import dev.cloudrun.port.RemoteExecutor.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsmStatusMappingTest {

    @ParameterizedTest
    @EnumSource(value = CommandInvocationStatus.class, names = {"PENDING", "IN_PROGRESS", "DELAYED", "CANCELLING"})
    void runningStatesAreNotFinished(CommandInvocationStatus status) {
        assertThat(SsmRemoteExecutor.toResult(status, -1, "", "")).isNull();
    }

    @Test
    void successForcesExitCodeZero() {
        CommandResult result = SsmRemoteExecutor.toResult(CommandInvocationStatus.SUCCESS, null, "out", "");
        assertThat(result).isEqualTo(new CommandResult(Status.SUCCESS, 0, "out", ""));
    }

    @Test
    void failedKeepsTheScriptExitCodeAndStderr() {
        CommandResult result = SsmRemoteExecutor.toResult(CommandInvocationStatus.FAILED, 3, "", "boom");
        assertThat(result).isEqualTo(new CommandResult(Status.FAILED, 3, "", "boom"));
    }

    @Test
    void timedOutAndCancelledAreDistinct() {
        assertThat(SsmRemoteExecutor.toResult(CommandInvocationStatus.TIMED_OUT, -1, "", "").status())
                .isEqualTo(Status.TIMED_OUT);
        assertThat(SsmRemoteExecutor.toResult(CommandInvocationStatus.CANCELLED, -1, "", "").status())
                .isEqualTo(Status.CANCELLED);
    }

    @Test
    void missingResponseCodeBecomesMinusOne() {
        assertThat(SsmRemoteExecutor.toResult(CommandInvocationStatus.FAILED, null, "", "").exitCode()).isEqualTo(-1);
    }

    @Test
    void unknownStatusIsRejectedInsteadOfGuessed() {
        assertThatThrownBy(() -> SsmRemoteExecutor.toResult(CommandInvocationStatus.UNKNOWN_TO_SDK_VERSION, 0, "", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
