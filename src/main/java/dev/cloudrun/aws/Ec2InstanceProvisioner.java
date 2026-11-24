package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.InstanceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "cloudrun.worker", name = "compute", havingValue = "aws")
public class Ec2InstanceProvisioner implements InstanceProvisioner {

    public static final String TAG_MANAGED = "cloudrun:managed";
    public static final String TAG_JOB_ID = "cloudrun:job-id";
    public static final String TAG_ATTEMPT = "cloudrun:attempt";

    private static final Logger log = LoggerFactory.getLogger(Ec2InstanceProvisioner.class);
    private static final Duration TERMINATE_TIMEOUT = Duration.ofSeconds(60);

    private final Ec2Client ec2;
    private final CloudrunProperties.Aws aws;
    private final Duration provisioningTimeout;

    public Ec2InstanceProvisioner(Ec2Client ec2, CloudrunProperties props) {
        this.ec2 = ec2;
        this.aws = props.aws();
        this.provisioningTimeout = Duration.ofSeconds(props.worker().provisioningTimeoutSeconds());
    }

    @Override
    public String provision(UUID jobId, int attempt) {
        var request = RunInstancesRequest.builder()
                .imageId(aws.amiId())
                .instanceType(aws.instanceType())
                .minCount(1)
                .maxCount(1)
                .clientToken(jobId + "-" + attempt)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(tag(TAG_MANAGED, "true"), tag(TAG_JOB_ID, jobId.toString()),
                                tag(TAG_ATTEMPT, String.valueOf(attempt)))
                        .build());
        if (aws.instanceProfile() != null && !aws.instanceProfile().isBlank()) {
            request.iamInstanceProfile(IamInstanceProfileSpecification.builder().name(aws.instanceProfile()).build());
        }

        String instanceId = ec2.runInstances(request.build()).instances().get(0).instanceId();
        log.info("Launched instance {}", instanceId);
        try {
            ec2.waiter().waitUntilInstanceRunning(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build(),
                    WaiterOverrideConfiguration.builder().waitTimeout(provisioningTimeout).build());
        } catch (RuntimeException e) {
            // The id is not handed to the caller, so nobody else can clean it up
            terminateQuietly(instanceId);
            throw e;
        }
        return instanceId;
    }

    @Override
    public void terminate(String instanceId) {
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
        ec2.waiter().waitUntilInstanceTerminated(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build(),
                WaiterOverrideConfiguration.builder().waitTimeout(TERMINATE_TIMEOUT).build());
        log.info("Terminated instance {}", instanceId);
    }

    @Override
    public List<String> listManaged() {
        var request = DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder().name("tag:" + TAG_MANAGED).values("true").build(),
                        Filter.builder().name("instance-state-name")
                                .values("pending", "running", "stopping", "stopped", "shutting-down").build())
                .build();
        return ec2.describeInstancesPaginator(request).reservations().stream()
                .flatMap(r -> r.instances().stream())
                .map(i -> i.instanceId())
                .toList();
    }

    private void terminateQuietly(String instanceId) {
        try {
            ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
        } catch (RuntimeException e) {
            log.error("Could not terminate half-provisioned instance {}", instanceId, e);
        }
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }
}
