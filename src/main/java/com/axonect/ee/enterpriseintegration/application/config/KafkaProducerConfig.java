package com.axonect.ee.enterpriseintegration.application.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {


    @Value("${spring.kafka.bootstrap-servers.dc}")
    private String bootstrapServersDC;

    @Value("${spring.kafka.bootstrap-servers.dr:${spring.kafka.bootstrap-servers.dc}}")
    private String bootstrapServersDR;

    @Value("${kafka.reply.topic}")
    private String replyTopic;

    @Value("${kafka.publishing.topic}")
    private String publishTopic;

    @Value("${kafka.group.id}")
    private String kafkaGroupId;

    @Value("${app.kafka.cluster.active:dc}")
    private String activeCluster;

    // -----------------------------------------------------------------------
    // DC Cluster Producer
    // -----------------------------------------------------------------------

    @Bean(name = "dcProducerFactory")
    public ProducerFactory<String, Object> dcProducerFactory() {
        return createProducerFactory(bootstrapServersDC);
    }

    @Bean(name = "dcKafkaTemplate")
    public KafkaTemplate<String, Object> dcKafkaTemplate() {
        return new KafkaTemplate<>(dcProducerFactory());
    }

    // -----------------------------------------------------------------------
    // DR Cluster Producer
    // -----------------------------------------------------------------------

    @Bean(name = "drProducerFactory")
    public ProducerFactory<String, Object> drProducerFactory() {
        return createProducerFactory(bootstrapServersDR);
    }

    @Bean(name = "drKafkaTemplate")
    public KafkaTemplate<String, Object> drKafkaTemplate() {
        return new KafkaTemplate<>(drProducerFactory());
    }

    // -----------------------------------------------------------------------
    // Shared producer factory builder
    // -----------------------------------------------------------------------

    private ProducerFactory<String, Object> createProducerFactory(String bootstrapServers) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        // Fail fast when broker is unreachable
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);         // metadata fetch timeout
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);   // per-request timeout
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);  // total send timeout (must be > REQUEST_TIMEOUT_MS)

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, String> replyContainer(
            ConsumerFactory<String, String> cf) {

        // 2. Force the consumer factory to use your actual cluster address instead of localhost
        if (cf instanceof DefaultKafkaConsumerFactory) {
            ((DefaultKafkaConsumerFactory<String, String>) cf)
                    .updateConfigs(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersDC));
        }

        ContainerProperties containerProperties = new ContainerProperties(replyTopic);
        String podId = System.getenv("HOSTNAME");
        if (podId == null || podId.isBlank()) {
            podId = java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        containerProperties.setGroupId("db-write-events-reply-reports" + podId);

        return new ConcurrentMessageListenerContainer<>(cf, containerProperties);
    }

    // -----------------------------------------------------------------------
    // Notification producer (String-typed)
    // -----------------------------------------------------------------------

    @Bean(name = "notificationProducerFactory")
    public ProducerFactory<String, String> notificationProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "dr".equalsIgnoreCase(activeCluster) ? bootstrapServersDR : bootstrapServersDC);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean(name = "kafkaTemplate")
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(notificationProducerFactory());
    }

    // -----------------------------------------------------------------------
    // Request-Reply infrastructure
    // -----------------------------------------------------------------------



    @Bean
    public ReplyingKafkaTemplate<String, Object, String> replyingKafkaTemplate(
            @Qualifier("dcProducerFactory") ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, String> replyContainer) {
        ReplyingKafkaTemplate<String, Object, String> template =
                new ReplyingKafkaTemplate<>(pf, replyContainer);
        // Hard ceiling — must be >= publishTimeoutMs in KafkaEventPublisher
        template.setDefaultReplyTimeout(Duration.ofSeconds(3));
        return template;
    }

    // -----------------------------------------------------------------------
    // Topic declarations
    // -----------------------------------------------------------------------

    @Bean
    public NewTopic dbWriteDCTopic() {
        return TopicBuilder.name(publishTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dbWriteDRTopic() {
        return TopicBuilder.name(publishTopic).partitions(3).replicas(1).build();
    }

}
