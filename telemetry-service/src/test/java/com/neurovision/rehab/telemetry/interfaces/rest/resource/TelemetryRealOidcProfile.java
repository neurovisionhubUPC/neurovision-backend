package com.neurovision.rehab.telemetry.interfaces.rest.resource;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class TelemetryRealOidcProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.enabled", "true",
                "quarkus.keycloak.devservices.enabled", "false",
                "quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus/",
                "quarkus.oidc.token.issuer", "https://server.example.com",
                "quarkus.oidc.client-id", "test-client",
                "quarkus.oidc.token.required-claims.client_id", "test-client");
    }
}
