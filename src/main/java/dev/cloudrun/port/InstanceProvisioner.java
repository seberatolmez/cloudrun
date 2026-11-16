package dev.cloudrun.port;

import java.util.UUID;

/** Port for creating and destroying the compute instance that runs one job. */
public interface InstanceProvisioner {

    String provision(UUID jobId);

    void terminate(String instanceId);
}
