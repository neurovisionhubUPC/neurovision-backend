package com.neurovision.rehab.analytics.interfaces.rest.resource;

import com.neurovision.rehab.analytics.infrastructure.integration.AnalyticsKafkaMongoTestResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local OIDC and Clinical HTTP with a dedicated MongoDB; no Kafka resource or consumer. */
public class AnalyticsSessionStreamsHttpProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        AnalyticsKafkaMongoTestResource.requireStreamDiscoveryOptIn();
        var overrides = new HashMap<>(new AnalyticsRealOidcProfile().getConfigOverrides());
        overrides.put("quarkus.devservices.enabled", "false");
        overrides.put("quarkus.kafka.devservices.enabled", "false");
        overrides.put("quarkus.mongodb.devservices.enabled", "false");
        overrides.put("mp.messaging.incoming.analytics-telemetry.enabled", "false");
        overrides.put("quarkus.http.access-log.enabled", "false");
        overrides.put("junit.jupiter.execution.parallel.enabled", "false");
        return overrides;
    }

    @Override
    public boolean disableGlobalTestResources() { return true; }

    @Override
    public List<TestResourceEntry> testResources() {
        AnalyticsKafkaMongoTestResource.requireStreamDiscoveryOptIn();
        return List.of(
                new TestResourceEntry(AnalyticsKafkaMongoTestResource.class, Map.of("mode", "mongo-only")),
                new TestResourceEntry(OidcWiremockTestResource.class),
                new TestResourceEntry(ClinicalHeatmapAuthorizationStubResource.class,
                        Map.of("stream-discovery-controls", "true")));
    }
}
