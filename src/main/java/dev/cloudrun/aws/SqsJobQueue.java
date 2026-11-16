package dev.cloudrun.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudrun.config.CloudrunProperties;
import dev.cloudrun.port.JobQueue;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;
import java.util.UUID;

@Component
public class SqsJobQueue implements JobQueue {

    private final SqsClient sqs;
    private final ObjectMapper mapper = new ObjectMapper();
    private String queueUrl;

    public SqsJobQueue(SqsClient sqs) {
        this.sqs = sqs;
    }

    // Set once by AwsInitializer during startup, before any receive/send
    public void setQueueUrl(String queueUrl) {
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(UUID jobId) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(toJson(jobId))
                .build());
    }

    @Override
    public List<QueueMessage> receive(int maxMessages, int waitSeconds) {
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds(waitSeconds)
                        .build())
                .messages();
        return messages.stream()
                .map(m -> new QueueMessage(m.messageId(), m.body(), m.receiptHandle()))
                .toList();
    }

    @Override
    public void delete(QueueMessage message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private String toJson(UUID jobId) {
        try {
            return mapper.writeValueAsString(new Payload(jobId));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize job message", e);
        }
    }

    record Payload(UUID jobId) {
    }
}
