package com.neurovision.rehab.telemetry.infrastructure.persistence.timescale;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Real Timescale image is intentionally not auto-started by the unit test suite. */
public class TimescaleTestResource implements QuarkusTestResourceLifecycleManager {
    private GenericContainer<?> database;

    @Override
    public Map<String, String> start() {
        database = new GenericContainer<>(DockerImageName.parse("timescale/timescaledb:2.30.1-pg17"))
                .withEnv("POSTGRES_DB", "telemetry")
                .withEnv("POSTGRES_USER", "telemetry")
                .withEnv("POSTGRES_PASSWORD", "telemetry-test-only")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
        database.start();
        String address = database.getHost() + ":" + database.getMappedPort(5432);
        return Map.of(
                "quarkus.datasource.active", "true",
                "quarkus.flyway.active", "true",
                "quarkus.flyway.migrate-at-start", "true",
                "quarkus.datasource.username", "telemetry",
                "quarkus.datasource.password", "telemetry-test-only",
                "quarkus.datasource.jdbc.url", "jdbc:postgresql://" + address + "/telemetry",
                "quarkus.datasource.reactive.url", "postgresql://" + address + "/telemetry",
                "mp.messaging.incoming.telemetry-batch-persist.enabled", "true");
    }

    @Override
    public void stop() {
        if (database != null) database.stop();
    }
}
