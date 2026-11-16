package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.InstanceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * M2 stand-in for Ec2InstanceProvisioner (M3). Delays are configurable so
 * M5 chaos tests can control the pace of the fake lifecycle.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "enabled", havingValue = "true")
public class FakeInstanceProvisioner implements InstanceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(FakeInstanceProvisioner.class);

    private final int delayMillis;

    public FakeInstanceProvisioner(CloudrunProperties props) {
        this.delayMillis = props.worker().fake().instanceDelayMillis();
    }

    @Override
    public String provision(UUID jobId) {
        sleep();
        String instanceId = "fake-i-" + jobId;
        log.info("Provisioned fake instance {}", instanceId);
        return instanceId;
    }

    @Override
    public void terminate(String instanceId) {
        sleep();
        log.info("Terminated fake instance {}", instanceId);
    }

    private void sleep() {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
