package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicitly selected, externally provisioned synthetic PostgreSQL; never a Dev Services fallback. */
public class ClinicalSessionReportHttpProfile extends ClinicalRealOidcProfile {
    static final String DATABASE = "nv_clinical_report_http_test";
    static final String USER = "clinical_report_http_test";
    static final String PASSWORD = "synthetic-report-http-test-only";
    static final String JDBC_PROPERTY = "clinical.report-http-test.jdbc-url";

    static String requireOptIn() {
        if (!"true".equals(System.getProperty("clinical.report-http-test.enabled"))
                || !"true".equals(System.getProperty("clinical.report-http-test.profile-selected")))
            throw new IllegalStateException("Explicit report HTTP profile and execution opt-in required");
        String url = System.getProperty(JDBC_PROPERTY, "");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/" + DATABASE))
            throw new IllegalStateException("Explicit dedicated loopback report HTTP PostgreSQL URL required");
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port < 1 || port > 65535) throw new IllegalStateException("Invalid dedicated PostgreSQL port");
        // Do not let direct caller overrides replace the profile/resource-owned local destinations.
        for (String key : List.of("clinical.profile.base-url", "clinical.analytics.base-url", "keycloak.url",
                "quarkus.oidc.auth-server-url", "quarkus.datasource.jdbc.url", "quarkus.datasource.username",
                "quarkus.datasource.password", "quarkus.http.host", "quarkus.http.test-host")) {
            if (System.getProperty(key) != null)
                throw new IllegalStateException("Direct destination overrides are not allowed for this suite");
        }
        return url;
    }

    static void requireLoopbackOrigin(String origin) {
        URI uri = URI.create(origin);
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getRawPath().isEmpty() || "/auth".equals(uri.getRawPath())))
            throw new IllegalStateException("Local test peer origin required");
    }

    @Override public boolean disableGlobalTestResources() { return true; }

    @Override public List<TestResourceEntry> testResources() {
        requireOptIn();
        return List.of(new TestResourceEntry(GuardedOidc.class),
                new TestResourceEntry(SessionReportPeersTestResource.class));
    }

    @Override public Map<String, String> getConfigOverrides() {
        String url = requireOptIn();
        var config = new HashMap<>(super.getConfigOverrides());
        config.putAll(Map.ofEntries(
                Map.entry("quarkus.datasource.jdbc.url", url + "?connectTimeout=5&socketTimeout=5"),
                Map.entry("quarkus.datasource.username", USER),
                Map.entry("quarkus.datasource.password", PASSWORD),
                Map.entry("quarkus.datasource.jdbc.acquisition-timeout", "5S"),
                Map.entry("quarkus.devservices.enabled", "false"),
                Map.entry("quarkus.datasource.devservices.enabled", "false"),
                Map.entry("quarkus.kafka.devservices.enabled", "false"),
                Map.entry("mp.messaging.incoming.patient-account-invited.enabled", "false"),
                Map.entry("quarkus.hibernate-orm.schema-management.strategy", "none"),
                Map.entry("quarkus.hibernate-orm.sql-load-script", "no-file"),
                Map.entry("quarkus.hibernate-orm.log.sql", "false"),
                Map.entry("quarkus.flyway.migrate-at-start", "false"),
                Map.entry("quarkus.flyway.clean-at-start", "false"),
                Map.entry("quarkus.flyway.clean-disabled", "true"),
                Map.entry("quarkus.flyway.baseline-at-start", "false"),
                Map.entry("quarkus.flyway.repair-at-start", "false"),
                Map.entry("quarkus.http.host", "127.0.0.1"),
                Map.entry("quarkus.http.test-host", "127.0.0.1"),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.http.access-log.enabled", "false"),
                Map.entry("quarkus.management.enabled", "false"),
                Map.entry("clinical.profile.base-url", "${clinical.report-http-test.peer-origin}"),
                Map.entry("clinical.analytics.base-url", "${clinical.report-http-test.peer-origin}"),
                Map.entry("clinical.profile.timeout-ms", "1000"),
                Map.entry("clinical.analytics.timeout-ms", "1000")));
        return Map.copyOf(config);
    }

    /** Only discovery and the existing synthetic public key; never starts the upstream WireMock server. */
    public static class GuardedOidc implements QuarkusTestResourceLifecycleManager {
        static final String DISCOVERY = "/auth/realms/quarkus/.well-known/openid-configuration";
        static final String CERTS = "/auth/realms/quarkus/protocol/openid-connect/certs";
        private static final int MAX_PUBLIC_KEY_BYTES = 4096;
        private final ServerFactory factory;
        private HttpServer server;
        private byte[] discovery;
        private byte[] jwks;

        @FunctionalInterface
        interface ServerFactory {
            HttpServer create(InetSocketAddress address) throws IOException;
        }

        public GuardedOidc() { this(address -> HttpServer.create(address, 0)); }

        // A fake server factory lets plain tests inspect the requested binding without opening a socket.
        GuardedOidc(ServerFactory factory) { this.factory = Objects.requireNonNull(factory); }

        @Override public Map<String, String> start() {
            requireOptIn();
            if (server != null) throw new IllegalStateException("Local OIDC test resource already started");
            try {
                jwks = loadPublicJwks();
                server = factory.create(new InetSocketAddress("127.0.0.1", 0));
                InetSocketAddress bound = server.getAddress();
                if (bound == null || bound.getAddress() == null
                        || !"127.0.0.1".equals(bound.getAddress().getHostAddress())
                        || bound.getPort() < 1 || bound.getPort() > 65535)
                    throw new IllegalStateException("Explicit IPv4 loopback OIDC binding required");
                String origin = "http://127.0.0.1:" + bound.getPort();
                requireLoopbackOrigin(origin);
                discovery = ("{\"issuer\":\"https://server.example.com\",\"jwks_uri\":\""
                        + origin + CERTS + "\"}").getBytes(StandardCharsets.UTF_8);
                server.createContext("/", this::respond);
                server.start();
                return Map.of("keycloak.url", origin + "/auth",
                        "smallrye.jwt.sign.key.location", "privateKey.jwk");
            } catch (IOException | RuntimeException | Error failure) {
                try { stop(); } catch (RuntimeException | Error ignored) { /* State is cleared in stop's finally. */ }
                throw new IllegalStateException("Local OIDC test resource startup failed");
            }
        }

        private static byte[] loadPublicJwks() throws IOException {
            try (var input = GuardedOidc.class.getResourceAsStream("/publicKey.jwk")) {
                if (input == null) throw new IOException("Synthetic public key unavailable");
                byte[] bytes = input.readNBytes(MAX_PUBLIC_KEY_BYTES + 1);
                if (bytes.length > MAX_PUBLIC_KEY_BYTES) throw new IOException("Synthetic public key too large");
                var mapper = new ObjectMapper();
                var key = mapper.readTree(bytes);
                // Reject private material or an unexpected key shape, even if the classpath changes.
                if (key == null || !key.isObject() || key.size() != 4 || !"RSA".equals(key.path("kty").asText()))
                    throw new IOException("Unexpected synthetic public key");
                for (String field : List.of("kid", "kty", "n", "e")) {
                    if (!key.path(field).isTextual() || key.path(field).asText().isBlank())
                        throw new IOException("Unexpected synthetic public key");
                }
                return mapper.writeValueAsBytes(mapper.createObjectNode().set("keys",
                        mapper.createArrayNode().add(key)));
            }
        }

        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getRawPath();
                byte[] body = null;
                if ("GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() == null) {
                    if (DISCOVERY.equals(path)) body = discovery;
                    else if (CERTS.equals(path)) body = jwks;
                }
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override public void stop() {
            HttpServer owned = server;
            server = null;
            try { if (owned != null) owned.stop(0); }
            finally { discovery = null; jwks = null; }
        }
    }
}
