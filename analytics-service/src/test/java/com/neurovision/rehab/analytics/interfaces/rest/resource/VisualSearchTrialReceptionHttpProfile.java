package com.neurovision.rehab.analytics.interfaces.rest.resource;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real inbound HTTP/OIDC, production composition and outbound Clinical HTTP; test-only storage. */
public class VisualSearchTrialReceptionHttpProfile implements QuarkusTestProfile {
    static void requireOptIn() {
        if (!"true".equals(System.getProperty("analytics.visual-search-reception.it.profile-selected"))
                || !"true".equals(System.getProperty("analytics.visual-search-reception.it.enabled")))
            throw new IllegalStateException("Explicit Visual Search reception profile and execution opt-in required");
        for (String key : List.of("analytics.clinical.base-url", "analytics.clinical.timeout-ms",
                "quarkus.mongodb.connection-string", "quarkus.mongodb.active", "quarkus.mongodb.devservices.enabled",
                "quarkus.oidc.enabled", "quarkus.oidc.auth-server-url", "quarkus.oidc.token.issuer",
                "quarkus.oidc.client-id", "quarkus.oidc.token.required-claims.client_id",
                "quarkus.oidc.roles.role-claim-path", "keycloak.url", "smallrye.jwt.sign.key.location",
                "quarkus.http.host", "quarkus.http.test-host", "quarkus.http.test-port",
                "mp.messaging.incoming.analytics-telemetry.enabled"))
            if (System.getProperty(key) != null)
                throw new IllegalStateException("Direct destination or integration overrides are not allowed");
    }

    @Override public boolean disableGlobalTestResources() { return true; }

    @Override public Set<Class<?>> getEnabledAlternatives() {
        requireOptIn();
        return Set.of(VisualSearchTrialStoreStub.class);
    }

    @Override public List<TestResourceEntry> testResources() {
        requireOptIn();
        return List.of(new TestResourceEntry(OidcWiremockTestResource.class),
                new TestResourceEntry(ClinicalHeatmapAuthorizationStubResource.class,
                        Map.of("visual-search-reception-controls", "true", "visual-search-summary-controls", "true")));
    }

    @Override public Map<String, String> getConfigOverrides() {
        requireOptIn();
        var config = new HashMap<>(new AnalyticsRealOidcProfile().getConfigOverrides());
        config.putAll(Map.ofEntries(
                Map.entry("quarkus.devservices.enabled", "false"),
                Map.entry("quarkus.kafka.devservices.enabled", "false"),
                Map.entry("quarkus.mongodb.devservices.enabled", "false"),
                Map.entry("quarkus.mongodb.active", "false"),
                Map.entry("quarkus.mongodb.health.enabled", "false"),
                Map.entry("quarkus.mongodb.connection-string", "mongodb://127.0.0.1:65532/analytics_test"),
                Map.entry("mp.messaging.incoming.analytics-telemetry.enabled", "false"),
                Map.entry("kafka.bootstrap.servers", "127.0.0.1:65533"),
                Map.entry("analytics.clinical.timeout-ms", "1000"),
                Map.entry("quarkus.http.host", "127.0.0.1"),
                Map.entry("quarkus.http.test-host", "127.0.0.1"),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.http.access-log.enabled", "false"),
                Map.entry("quarkus.management.enabled", "false"),
                Map.entry("junit.jupiter.execution.parallel.enabled", "false")));
        return config;
    }
}
