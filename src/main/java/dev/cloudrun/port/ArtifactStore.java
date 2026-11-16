package dev.cloudrun.port;

import java.util.UUID;

/** Port for storing job artifacts (script for now; stdout/stderr/exit code come in M4). */
public interface ArtifactStore {

    void uploadScript(UUID jobId, String script);
}
