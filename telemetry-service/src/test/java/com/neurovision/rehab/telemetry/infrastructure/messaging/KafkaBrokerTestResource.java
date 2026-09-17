package com.neurovision.rehab.telemetry.infrastructure.messaging;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaBrokerTestResource implements QuarkusTestResourceLifecycleManager {
    private KafkaContainer broker;

    @Override
    public Map<String, String> start() {
        broker = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
        broker.start();
        var properties = new Properties();
        properties.put("bootstrap.servers", broker.getBootstrapServers());
        try (var admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(new NewTopic("telemetry-batch-accepted.v1", 3, (short) 1)))
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

    @Override
    public void stop() {
        if (broker != null) broker.stop();
    }
}
