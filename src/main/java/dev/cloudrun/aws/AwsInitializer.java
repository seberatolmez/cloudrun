package dev.cloudrun.aws;

import dev.cloudrun.config.CloudrunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.Map;

/**
 * Idempotent get-or-create of the S3 bucket and the SQS queue + DLQ so the
 * app is self-contained without the m1 scripts. Retries briefly because Floci
 * can come up slower than the app.
 */
@Component
public class AwsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AwsInitializer.class);
    private static final int ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_MILLIS = 2000;

    private final S3Client s3;
    private final SqsClient sqs;
    private final SqsJobQueue jobQueue;
    private final CloudrunProperties props;

    public AwsInitializer(S3Client s3, SqsClient sqs, SqsJobQueue jobQueue, CloudrunProperties props) {
        this.s3 = s3;
        this.sqs = sqs;
        this.jobQueue = jobQueue;
        this.props = props;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                ensureBucket();
                String queueUrl = ensureQueues();
                jobQueue.setQueueUrl(queueUrl);
                log.info("AWS resources ready: bucket={}, queue={}", props.aws().bucket(), queueUrl);
                return;
            } catch (Exception e) {
                if (attempt == ATTEMPTS) {
                    throw new IllegalStateException("AWS resources not reachable after "
                            + ATTEMPTS + " attempts", e);
                }
                log.warn("AWS bootstrap attempt {} failed ({}), retrying in {} ms",
                        attempt, e.getMessage(), RETRY_BACKOFF_MILLIS);
                sleep();
            }
        }
    }

    private void ensureBucket() {
        var bucket = props.aws().bucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created S3 bucket {}", bucket);
        }
    }

    private String ensureQueues() {
        var aws = props.aws();
        String dlqUrl = getOrCreateQueue(aws.dlqName(), null);
        String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}";
        return getOrCreateQueue(aws.queueName(), redrive);
    }

    private String getOrCreateQueue(String name, String redrivePolicy) {
        try {
            return sqs.getQueueUrl(builder -> builder.queueName(name)).queueUrl();
        } catch (QueueDoesNotExistException e) {
            var request = CreateQueueRequest.builder().queueName(name);
            if (redrivePolicy != null) {
                request.attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy));
            }
            return sqs.createQueue(request.build()).queueUrl();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
