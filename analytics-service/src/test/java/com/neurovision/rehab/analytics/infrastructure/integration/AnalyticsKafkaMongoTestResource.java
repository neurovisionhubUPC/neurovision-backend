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
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

public class AnalyticsKafkaMongoTestResource implements QuarkusTestResourceLifecycleManager {
    public static final String STREAM_DISCOVERY_OPT_IN = "analytics.stream-discovery.it.enabled";
    private KafkaContainer kafka;
    private MongoDBContainer mongo;
    private boolean mongoOnly;
    private Properties pullConfiguration;
    private Object previousPullPolicy;

    @Override
    public void init(Map<String, String> args) {
        mongoOnly = "mongo-only".equals(args.get("mode"));
    }

    @Override
    public Map<String, String> start() {
        if (mongoOnly) return startMongoOnly();
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

    public static void requireStreamDiscoveryOptIn() {
        if (!"true".equals(System.getProperty(STREAM_DISCOVERY_OPT_IN))) {
            throw new IllegalStateException("Stream discovery HTTP integration requires explicit opt-in");
        }
    }

    private Map<String, String> startMongoOnly() {
        requireStreamDiscoveryOptIn(); // Must precede Testcontainers configuration or Docker access.
        try {
            var configuration = TestcontainersConfiguration.getInstance();
            if (Boolean.parseBoolean(System.getenv("TESTCONTAINERS_RYUK_DISABLED"))
                    || configuration.environmentSupportsReuse() || configuration.isDisableChecks()) {
                throw new IllegalStateException("Isolated integration requires cleanup and startup checks");
            }
            // Public in-memory API only: never updateUserConfig (it writes the user's home directory).
            pullConfiguration = configuration.getUserProperties();
            previousPullPolicy = pullConfiguration.put("pull.policy", NeverPullPolicy.class.getName());
            if (!NeverPullPolicy.class.getName().equals(configuration.getImagePullPolicy())
                    || !(PullPolicy.defaultPolicy() instanceof NeverPullPolicy)) {
                throw new IllegalStateException("Cannot establish global no-pull policy before Docker access");
            }
            mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0.14-noble"))
                    .withImagePullPolicy(new NeverPullPolicy()).withReuse(false);
            mongo.start();
            return Map.of(
                    "quarkus.mongodb.active", "true",
                    "quarkus.mongodb.connection-string", mongo.getConnectionString(),
                    "quarkus.mongodb.database", "analytics_streams_it_" + UUID.randomUUID().toString().replace("-", ""),
                    "quarkus.mongodb.health.enabled", "true",
                    "kafka.bootstrap.servers", "127.0.0.1:65533",
                    "mp.messaging.incoming.analytics-telemetry.enabled", "false");
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    /** Global policy includes helper images and Ryuk, not only the MongoDB image. */
    public static final class NeverPullPolicy implements ImagePullPolicy {
        @Override
        public boolean shouldPull(DockerImageName imageName) { return false; }
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
        if (!mongoOnly) {
            if (mongo != null) mongo.stop();
            if (kafka != null) kafka.stop();
            return;
        }
        try {
            if (mongo != null) mongo.stop();
        } finally {
            try {
                if (kafka != null) kafka.stop();
            } finally {
                if (pullConfiguration != null) {
                    if (previousPullPolicy == null) pullConfiguration.remove("pull.policy");
                    else pullConfiguration.put("pull.policy", previousPullPolicy);
                    pullConfiguration = null;
                }
            }
        }
    }
}
