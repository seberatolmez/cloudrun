package dev.cloudrun.orchestrator;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.InstanceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Ec2InstanceProvisioner. Delays are configurable so tests can
 * control the pace of the fake lifecycle.
 */
@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "compute", havingValue = "fake")
public class FakeInstanceProvisioner implements InstanceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(FakeInstanceProvisioner.class);

    private final int delayMillis;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public FakeInstanceProvisioner(CloudrunProperties props) {
        this.delayMillis = props.worker().fake().instanceDelayMillis();
    }

    @Override
    public String provision(UUID jobId, int attempt) {
        sleep();
        String instanceId = "fake-i-" + jobId;
        running.add(instanceId);
        log.info("Provisioned fake instance {}", instanceId);
        return instanceId;
    }

    @Override
    public void terminate(String instanceId) {
        sleep();
        running.remove(instanceId);
        log.info("Terminated fake instance {}", instanceId);
    }

    @Override
    public List<String> listManaged() {
        return List.copyOf(running);
    }

    private void sleep() {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
