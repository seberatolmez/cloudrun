package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.ArtifactStore;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class S3ArtifactStore implements ArtifactStore {

    private final S3Client s3;
    private final String bucket;

    public S3ArtifactStore(S3Client s3, CloudrunProperties props) {
        this.s3 = s3;
        this.bucket = props.aws().bucket();
    }

    @Override
    public void uploadScript(UUID jobId, String script) {
        s3.putObject(builder -> builder.bucket(bucket).key(scriptKey(jobId)),
                RequestBody.fromString(script, StandardCharsets.UTF_8));
    }

    public static String scriptKey(UUID jobId) {
        return "jobs/" + jobId + "/script.py";
    }
}
