package dev.cloudrun.port;

import java.util.List;
import java.util.UUID;

/** Port for the job queue; SQS adapter in M2, interface only for testability. */
public interface JobQueue {

    void send(UUID jobId);

    List<QueueMessage> receive(int maxMessages, int waitSeconds);

    void delete(QueueMessage message);

    record QueueMessage(String messageId, String body, String receiptHandle) {
    }
}
