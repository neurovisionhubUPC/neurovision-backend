package com.neurovision.rehab.analytics.interfaces.rest.resource;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

/** Explicit local transport/CDI check, without database, messaging or identity-provider resources. */
public class VisualSearchAuthorizationHttpProfile implements QuarkusTestProfile {
    static void requireOptIn() {
        if (!"true".equals(System.getProperty("analytics.visual-search-authorization.it.profile-selected"))
                || !"true".equals(System.getProperty("analytics.visual-search-authorization.it.enabled")))
            throw new IllegalStateException("Explicit Visual Search HTTP profile and execution opt-in required");
        for (String key : List.of("analytics.clinical.base-url", "quarkus.http.host", "quarkus.http.test-host",
                "quarkus.http.test-port", "quarkus.mongodb.connection-string", "quarkus.mongodb.active",
                "quarkus.oidc.enabled", "quarkus.oidc.auth-server-url", "mp.messaging.incoming.analytics-telemetry.enabled"))
            if (System.getProperty(key) != null)
                throw new IllegalStateException("Direct destination or integration overrides are not allowed");
    }

    @Override public boolean disableGlobalTestResources() { return true; }

    @Override public List<TestResourceEntry> testResources() {
        requireOptIn();
        return List.of(new TestResourceEntry(ClinicalHeatmapAuthorizationStubResource.class,
                Map.of("visual-search-controls", "true", "visual-search-summary-controls", "true")));
    }

    @Override public Map<String, String> getConfigOverrides() {
        requireOptIn();
        return Map.ofEntries(
                Map.entry("quarkus.devservices.enabled", "false"),
                Map.entry("quarkus.kafka.devservices.enabled", "false"),
                Map.entry("quarkus.mongodb.devservices.enabled", "false"),
                Map.entry("quarkus.mongodb.active", "false"),
                Map.entry("quarkus.mongodb.health.enabled", "false"),
                Map.entry("quarkus.mongodb.connection-string", "mongodb://127.0.0.1:65532/analytics_test"),
                Map.entry("quarkus.oidc.enabled", "false"),
                Map.entry("kafka.bootstrap.servers", "127.0.0.1:65533"),
                Map.entry("mp.messaging.incoming.analytics-telemetry.enabled", "false"),
                Map.entry("analytics.clinical.timeout-ms", "1000"),
                Map.entry("quarkus.http.host", "127.0.0.1"),
                Map.entry("quarkus.http.test-host", "127.0.0.1"),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.http.access-log.enabled", "false"),
                Map.entry("quarkus.management.enabled", "false"),
                Map.entry("junit.jupiter.execution.parallel.enabled", "false"));
    }
}
