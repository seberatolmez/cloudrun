package dev.cloudrun.port;

import java.util.List;
import java.util.UUID;

/** Port for creating and destroying the compute instance that runs one job. */
public interface InstanceProvisioner {

    /** Returns the id of a running instance tagged for the job; never returns an instance it could not keep track of. */
    String provision(UUID jobId, int attempt);

    void terminate(String instanceId);

    /** Ids of managed instances that are not terminated, for cleanup and test assertions. */
    List<String> listManaged();
}
