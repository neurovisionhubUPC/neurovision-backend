package com.neurovision.rehab.telemetry.infrastructure.messaging;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaBrokerTestResource implements QuarkusTestResourceLifecycleManager {
    private KafkaContainer broker;

    @Override
    public Map<String, String> start() {
        broker = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        broker.start();
        var properties = new Properties();
        properties.put("bootstrap.servers", broker.getBootstrapServers());
        try (var admin = AdminClient.create(properties)) {
            var topics = new ArrayList<>(List.of(new NewTopic("telemetry-batch-accepted.v1", 3, (short) 1)));
            if (provisionDlq()) topics.add(new NewTopic("telemetry-batch-accepted.v1.dlq", 3, (short) 1));
            topics.add(new NewTopic("neurovision.dlq-replay-audit.v1", 1, (short) 1));
            admin.createTopics(topics)
                    .all().get(Duration.ofSeconds(15).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            broker.stop();
            throw new IllegalStateException("Cannot provision test telemetry Kafka topic", failure);
        }
        return Map.of("kafka.bootstrap.servers", broker.getBootstrapServers(),
                "mp.messaging.outgoing.telemetry-batch-accepted.delivery.timeout.ms", "5000",
                "mp.messaging.outgoing.telemetry-batch-accepted.request.timeout.ms", "2000",
                "telemetry.kafka.ack-timeout-ms", "6000");
    }

    protected boolean provisionDlq() {
        return true;
    }

    @Override
    public void stop() {
        if (broker != null) broker.stop();
    }
}
