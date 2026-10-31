package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.smallrye.jwt.build.Jwt;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Plain offline tests: no Quarkus extension, real server, socket, or database. */
@ResourceLock("java.lang.System.properties")
class ClinicalSessionReportOidcResourceTest {
    private static final String ENABLED = "clinical.report-http-test.enabled";
    private static final String SELECTED = "clinical.report-http-test.profile-selected";
    private static final String JDBC = ClinicalSessionReportHttpProfile.JDBC_PROPERTY;
    private static final List<String> DIRECT = List.of("clinical.profile.base-url", "clinical.analytics.base-url",
            "keycloak.url", "quarkus.oidc.auth-server-url", "quarkus.datasource.jdbc.url",
            "quarkus.datasource.username", "quarkus.datasource.password", "quarkus.http.host", "quarkus.http.test-host");
    private final Map<String, String> original = new HashMap<>();
    private final FakeServer fake = new FakeServer();
    private InetSocketAddress requested;
    private int creations;
    private ClinicalSessionReportHttpProfile.GuardedOidc resource;

    @BeforeEach void configureSyntheticOptIn() {
        var keys = new ArrayList<>(DIRECT);
        keys.addAll(List.of(ENABLED, SELECTED, JDBC, "smallrye.jwt.sign.key.location"));
        for (String key : keys) {
            original.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        System.setProperty(ENABLED, "true");
        System.setProperty(SELECTED, "true");
        System.setProperty(JDBC, "jdbc:postgresql://127.0.0.1:55439/nv_clinical_report_http_test");
        resource = new ClinicalSessionReportHttpProfile.GuardedOidc(address -> {
            assertEquals("127.0.0.1", address.getAddress().getHostAddress());
            assertEquals(0, address.getPort());
            assertFalse(fake.started);
            requested = address;
            creations++;
            return fake;
        });
    }

    @AfterEach void restoreProperties() {
        resource.stop();
        original.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test void doesNotInheritTheWildcardBindingServer() {
        assertFalse(OidcWiremockTestResource.class.isAssignableFrom(
                ClinicalSessionReportHttpProfile.GuardedOidc.class),
                "The report resource must own its explicit loopback binding");
    }

    @Test void rejectsMissingOptInsBeforeFactoryCanBind() {
        for (String key : List.of(ENABLED, SELECTED)) {
            System.clearProperty(key);
            assertThrows(IllegalStateException.class, resource::start);
            assertEquals(0, creations);
            System.setProperty(key, "true");
        }
    }

    @Test void preservesDatabaseAndDirectOverrideGuardsBeforeFactoryCanBind() {
        String valid = System.getProperty(JDBC);
        for (String url : List.of("", "jdbc:postgresql://localhost:55439/nv_clinical_report_http_test",
                "jdbc:postgresql://127.0.0.1:55439/clinical", valid + "?user=other",
                "jdbc:postgresql://127.0.0.1:0/nv_clinical_report_http_test",
                "jdbc:postgresql://127.0.0.1:65536/nv_clinical_report_http_test")) {
            System.setProperty(JDBC, url);
            assertThrows(IllegalStateException.class, resource::start);
            assertEquals(0, creations);
        }
        System.setProperty(JDBC, valid);
        for (String key : DIRECT) {
            System.setProperty(key, "rejected-direct-override");
            assertThrows(IllegalStateException.class, resource::start);
            assertEquals(0, creations);
            System.clearProperty(key);
        }
    }

    @Test void requestsExactIpv4LoopbackEphemeralPortBeforeFakeStart() throws Exception {
        var config = resource.start();
        assertEquals("127.0.0.1", requested.getAddress().getHostAddress());
        assertEquals(0, requested.getPort());
        assertTrue(fake.started);
        assertEquals(1, creations);
        assertEquals("http://127.0.0.1:49173/auth", config.get("keycloak.url"));
        assertEquals("privateKey.jwk", config.get("smallrye.jwt.sign.key.location"));
        assertThrows(IllegalStateException.class, resource::start);
        assertEquals(1, creations);

        var discovery = exchange("GET", ClinicalSessionReportHttpProfile.GuardedOidc.DISCOVERY);
        JsonNode json = new ObjectMapper().readTree(discovery.body.toByteArray());
        assertEquals(2, json.size());
        assertEquals("https://server.example.com", json.path("issuer").asText());
        assertEquals("http://127.0.0.1:49173" + ClinicalSessionReportHttpProfile.GuardedOidc.CERTS,
                json.path("jwks_uri").asText());
    }

    @Test void servesOnlyExactGetDiscoveryAndJwksWithoutQueries() throws Exception {
        resource.start();
        for (String path : List.of(ClinicalSessionReportHttpProfile.GuardedOidc.DISCOVERY,
                ClinicalSessionReportHttpProfile.GuardedOidc.CERTS)) {
            var accepted = exchange("GET", path);
            assertEquals(200, accepted.code);
            assertEquals("application/json", accepted.response.getFirst("Content-Type"));
            assertEquals("no-store", accepted.response.getFirst("Cache-Control"));
            assertEquals(accepted.body.size(), accepted.length);
            assertTrue(accepted.body.size() <= 8192);
            for (String method : List.of("POST", "PUT", "DELETE", "HEAD", "OPTIONS")) rejected(method, path);
            rejected("GET", path + "?ignored=true");
            rejected("GET", path + "?");
            rejected("GET", path + "/");
        }
        for (String path : List.of("/", "/auth/realms/quarkus/token",
                "/auth/realms/quarkus/protocol/openid-connect/token/introspect",
                "/auth/realms/quarkus/protocol/openid-connect/userinfo")) rejected("GET", path);
    }

    @Test void rejectsUnexpectedActualBindingsAndStopsOnlyItsOwnServer() {
        for (InetSocketAddress address : List.of(new InetSocketAddress("0.0.0.0", 49173),
                new InetSocketAddress("::1", 49173), new InetSocketAddress("127.0.0.1", 0),
                InetSocketAddress.createUnresolved("localhost", 49173))) {
            fake.address = address;
            int before = fake.stops;
            assertThrows(IllegalStateException.class, resource::start);
            assertFalse(fake.started);
            assertEquals(before + 1, fake.stops);
            resource.stop();
            assertEquals(before + 1, fake.stops);
        }
    }

    @Test void cleansUpFakeStartFailureAndClearsResponses() throws Exception {
        fake.failStart = true;
        assertThrows(IllegalStateException.class, resource::start);
        assertEquals(1, fake.stops);
        assertFalse(fake.started);
        rejected("GET", ClinicalSessionReportHttpProfile.GuardedOidc.DISCOVERY);
        rejected("GET", ClinicalSessionReportHttpProfile.GuardedOidc.CERTS);
        resource.stop();
        assertEquals(1, fake.stops);
        fake.failStart = false;
        resource.start();
        assertTrue(fake.started);
        resource.stop();
        resource.stop();
        assertEquals(2, fake.stops);
        rejected("GET", ClinicalSessionReportHttpProfile.GuardedOidc.CERTS);
    }

    @Test void handlesFactoryFailureWithoutCreatingOrStoppingAnUnownedServer() {
        resource = new ClinicalSessionReportHttpProfile.GuardedOidc(address -> {
            throw new IOException("Synthetic factory failure");
        });
        assertThrows(IllegalStateException.class, resource::start);
        resource.stop();
        assertEquals(0, fake.stops);
    }

    @Test void publicOnlyJwksVerifiesExistingSignerAndRejectsTamperingOffline() throws Exception {
        var config = resource.start();
        var response = exchange("GET", ClinicalSessionReportHttpProfile.GuardedOidc.CERTS);
        var json = new ObjectMapper().readTree(response.body.toByteArray());
        assertEquals(1, json.size());
        assertEquals(1, json.path("keys").size());
        JsonNode key = json.path("keys").get(0);
        var fields = new HashSet<String>();
        key.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("kid", "kty", "n", "e"), fields);
        assertEquals("RSA", key.path("kty").asText());
        System.setProperty("smallrye.jwt.sign.key.location", config.get("smallrye.jwt.sign.key.location"));
        String token = Jwt.subject("synthetic-report-http-doctor").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", List.of("DOCTOR")).expiresAt(Instant.now().plusSeconds(60).getEpochSecond()).sign();
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        var decoder = Base64.getUrlDecoder();
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, decoder.decode(key.path("n").asText())),
                new BigInteger(1, decoder.decode(key.path("e").asText()))));
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        verifier.update(signed);
        assertTrue(verifier.verify(decoder.decode(parts[2])), "Synthetic signer must match public JWKS");
        signed[signed.length - 1] ^= 1;
        verifier.initVerify(publicKey);
        verifier.update(signed);
        assertFalse(verifier.verify(decoder.decode(parts[2])), "Tampered synthetic token must fail verification");
    }

    private FakeExchange exchange(String method, String path) throws Exception {
        var exchange = new FakeExchange(method, path);
        fake.handler.handle(exchange);
        assertTrue(exchange.closed);
        return exchange;
    }

    private void rejected(String method, String path) throws Exception {
        var exchange = exchange(method, path);
        assertEquals(404, exchange.code);
        assertEquals(0, exchange.body.size());
        assertEquals(-1, exchange.length);
    }

    private static final class FakeServer extends HttpServer {
        private InetSocketAddress address = new InetSocketAddress("127.0.0.1", 49173);
        private HttpHandler handler;
        private boolean started;
        private boolean failStart;
        private int stops;
        @Override public void bind(InetSocketAddress address, int backlog) { throw new AssertionError("No real bind"); }
        @Override public void start() {
            if (failStart) throw new IllegalStateException("Synthetic start failure");
            started = true;
        }
        @Override public void stop(int delay) { stops++; started = false; }
        @Override public InetSocketAddress getAddress() { return address; }
        @Override public HttpContext createContext(String path, HttpHandler handler) {
            assertEquals("/", path); this.handler = handler; return null;
        }
        @Override public HttpContext createContext(String path) { throw new UnsupportedOperationException(); }
        @Override public void removeContext(String path) { throw new UnsupportedOperationException(); }
        @Override public void removeContext(HttpContext context) { throw new UnsupportedOperationException(); }
        @Override public void setExecutor(Executor executor) { throw new UnsupportedOperationException(); }
        @Override public Executor getExecutor() { return null; }
    }

    private static final class FakeExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers response = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int code;
        private long length;
        private boolean closed;
        FakeExchange(String method, String path) { this.method = method; uri = URI.create(path); }
        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int code, long length) { this.code = code; this.length = length; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { throw new UnsupportedOperationException(); }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
