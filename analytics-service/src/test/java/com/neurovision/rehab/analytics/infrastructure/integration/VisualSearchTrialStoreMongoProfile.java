package com.neurovision.rehab.analytics.infrastructure.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

/** Explicit Mongo-only integration selection; no messaging, identity or remote peer resources. */
public class VisualSearchTrialStoreMongoProfile implements QuarkusTestProfile {
    public static void requireOptIn() {
        if (!"true".equals(System.getProperty("analytics.visual-search-store.it.profile-selected"))
                || !"true".equals(System.getProperty("analytics.visual-search-store.it.enabled")))
            throw new IllegalStateException("Explicit Visual Search Mongo profile and execution opt-in required");
        for (String key : List.of("quarkus.mongodb.connection-string", "quarkus.mongodb.database",
                "quarkus.mongodb.active", "analytics.visual-search.results-collection",
                "analytics.visual-search.persistence-timeout-ms", "quarkus.oidc.enabled",
                "quarkus.oidc.auth-server-url", "mp.messaging.incoming.analytics-telemetry.enabled",
                "quarkus.http.host", "quarkus.http.test-host", "quarkus.http.test-port"))
            if (System.getProperty(key) != null)
                throw new IllegalStateException("Direct integration overrides are not allowed");
    }

    @Override public boolean disableGlobalTestResources() { return true; }

    @Override public List<TestResourceEntry> testResources() {
        requireOptIn();
        return List.of(new TestResourceEntry(AnalyticsKafkaMongoTestResource.class,
                Map.of("mode", "mongo-only", "purpose", "visual-search")));
    }

    @Override public Map<String, String> getConfigOverrides() {
        requireOptIn();
        return Map.ofEntries(
                Map.entry("quarkus.devservices.enabled", "false"),
                Map.entry("quarkus.kafka.devservices.enabled", "false"),
                Map.entry("quarkus.mongodb.devservices.enabled", "false"),
                Map.entry("quarkus.oidc.enabled", "false"),
                Map.entry("mp.messaging.incoming.analytics-telemetry.enabled", "false"),
                Map.entry("analytics.visual-search.results-collection", "visual_search_trial_results_it"),
                Map.entry("analytics.visual-search.persistence-timeout-ms", "5000"),
                Map.entry("quarkus.http.host", "127.0.0.1"),
                Map.entry("quarkus.http.test-host", "127.0.0.1"),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.http.access-log.enabled", "false"),
                Map.entry("quarkus.management.enabled", "false"),
                Map.entry("junit.jupiter.execution.parallel.enabled", "false"));
    }
}
