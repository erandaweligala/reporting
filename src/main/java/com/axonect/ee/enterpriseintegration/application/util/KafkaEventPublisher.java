package com.axonect.ee.enterpriseintegration.application.util;

import com.axonect.ee.enterpriseintegration.application.transport.request.DBWriteRequestGeneric;
import com.axonect.ee.enterpriseintegration.application.transport.response.PublishResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaEventPublisher {

    @Qualifier("dcKafkaTemplate")
    private final KafkaTemplate<String, Object> dcKafkaTemplate;

    @Autowired
    private ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate;

    @Value("${app.kafka.publish.timeout-ms:2000}")
    private long publishTimeoutMs;

    @Value("${app.kafka.publish.retry.enabled:true}")
    private boolean retryEnabled;

    @Value("${app.kafka.publish.retry.max-attempts:2}")
    private int maxRetryAttempts;

    @Value("${kafka.reply.topic}")
    private String replyTopic;

    @Value("${kafka.publishing.topic}")
    private String publishTopic;



    public boolean publishWithBusinessAck(String topic,
                                          String cluster,
                                          String key,
                                          Object payload,
                                          String eventType) {
        int attempt = 0;

        while (attempt < maxRetryAttempts) {
            attempt++;
            try {
                ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);

                // Pass timeout directly — don't rely solely on template-level default
                RequestReplyFuture<String, Object, String> future =
                        replyingKafkaTemplate.sendAndReceive(record, Duration.ofMillis(publishTimeoutMs));

                // Broker ACK
                SendResult<String, Object> sendResult =
                        future.getSendFuture().get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                log.debug("[{}] Broker ACK received for {} event (key: '{}') – Partition: {}, Offset: {}",
                        cluster, eventType, key,
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset());

                // Consumer business-level reply — bounded by the duration passed above
                ConsumerRecord<String, String> reply = future.get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                String result = reply.value();
                if ("SUCCESS".equalsIgnoreCase(result)) {
                    log.info("[{}] Consumer confirmed success for {} event (key: '{}')",
                            cluster, eventType, key);
                    return true;
                } else {
                    log.warn("[{}] Consumer reported failure: '{}' for {} event (key: '{}'). Retrying...",
                            cluster, result, eventType, key);
                }

            } catch (Exception e) {
                log.error("[{}] Attempt {}/{} failed for {} event (key: '{}'): {}",
                        cluster, attempt, maxRetryAttempts, eventType, key, e.getMessage());
            }

            if (!retryEnabled || attempt >= maxRetryAttempts) {
                break;
            }

            try {
                long backoffMs = (long) Math.pow(2, attempt) * 100; // 200ms, 400ms
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.error("[{}] FINAL NACK for {} event (key: '{}') after {} attempts",
                cluster, eventType, key, attempt);
        return false;
    }

    // -----------------------------------------------------------------------
    // Public publish methods
    // -----------------------------------------------------------------------

    /**
     * Publish DB write event with business ACK.
     */
    public PublishResult publishDBWriteEvent(DBWriteRequestGeneric dbWriteEvent) {
        String key = dbWriteEvent.getUserName();

        boolean success = publishWithBusinessAck(publishTopic, "DC", key, dbWriteEvent, dbWriteEvent.getEventType());
        return PublishResult.builder()
                .dcSuccess(success)
                .drSuccess(false)
                .build();
    }
}
