package com.neurovision.rehab.analytics.infrastructure.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

public class AnalyticsKafkaMongoTestResource implements QuarkusTestResourceLifecycleManager {
    private KafkaContainer kafka;
    private MongoDBContainer mongo;

    @Override
    public Map<String, String> start() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0.14-noble"));
        try {
            kafka.start();
            mongo.start();
            createTopics();
            return Map.of(
                    "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                    "quarkus.mongodb.active", "true",
                    "quarkus.mongodb.connection-string", mongo.getConnectionString(),
                    "quarkus.mongodb.database", "analytics_it_" + UUID.randomUUID().toString().replace("-", ""),
                    "quarkus.mongodb.health.enabled", "true",
                    "mp.messaging.incoming.analytics-telemetry.enabled", "true",
                    "mp.messaging.incoming.analytics-telemetry.auto.commit.interval.ms", "100",
                    "analytics.consumer.persistence-timeout-ms", "5000",
                    "analytics.dlq.ack-timeout-ms", "6000");
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    private void createTopics() {
        var properties = new Properties();
        properties.put("bootstrap.servers", kafka.getBootstrapServers());
        try (var admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(
                            new NewTopic("telemetry-batch-accepted.v1", 3, (short) 1),
                            new NewTopic("analytics-telemetry.v1.dlq", 3, (short) 1)))
                    .all().get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot provision isolated Analytics Kafka topics", failure);
        }
    }

    @Override
    public void stop() {
        if (mongo != null) mongo.stop();
        if (kafka != null) kafka.stop();
    }
}
