package dev.cloudrun.aws;

import dev.cloudrun.testsupport.Floci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2InstanceProvisionerTest {

    private static Ec2Client ec2;
    private static Ec2InstanceProvisioner provisioner;

    @BeforeAll
    static void connect() {
        Floci.assumeRunning();
        ec2 = Floci.ec2();
        provisioner = new Ec2InstanceProvisioner(ec2, Floci.props());
    }

    @BeforeEach
    @AfterEach
    void noManagedInstancesLeftBehind() {
        provisioner.listManaged().forEach(provisioner::terminate);
    }

    @Test
    void provisionsTaggedRunningInstanceAndTerminatesIt() {
        UUID jobId = UUID.randomUUID();

        String instanceId = provisioner.provision(jobId, 1);

        Instance instance = describe(instanceId);
        assertThat(instance.state().name()).isEqualTo(InstanceStateName.RUNNING);
        assertThat(instance.tags()).anyMatch(t -> t.key().equals(Ec2InstanceProvisioner.TAG_MANAGED) && t.value().equals("true"));
        assertThat(instance.tags()).anyMatch(t -> t.key().equals(Ec2InstanceProvisioner.TAG_JOB_ID) && t.value().equals(jobId.toString()));
        assertThat(provisioner.listManaged()).contains(instanceId);

        provisioner.terminate(instanceId);

        assertThat(describe(instanceId).state().name()).isEqualTo(InstanceStateName.TERMINATED);
        assertThat(provisioner.listManaged()).doesNotContain(instanceId);
    }

    @Test
    void listManagedIgnoresInstancesWithoutTheManagedTag() {
        String foreign = ec2.runInstances(RunInstancesRequest.builder()
                .imageId(Floci.props().aws().amiId()).instanceType("t3.micro").minCount(1).maxCount(1).build())
                .instances().get(0).instanceId();
        try {
            assertThat(provisioner.listManaged()).doesNotContain(foreign);
        } finally {
            ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(foreign).build());
        }
    }

    private static Instance describe(String instanceId) {
        return ec2.describeInstances(DescribeInstancesRequest.builder().instanceIds(instanceId).build())
                .reservations().get(0).instances().get(0);
    }
}
