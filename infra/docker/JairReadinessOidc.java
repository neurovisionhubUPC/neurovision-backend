import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

// Readiness-only adaptation of ClinicalSessionReportHttpProfile.GuardedOidc.
final class JairReadinessOidc {
    private static final String ISSUER = "http://oidc-fixture:8080";
    private static final String DISCOVERY = "/.well-known/openid-configuration";
    private static final String KEYS = "/jwks";

    public static void main(String[] args) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"readiness-only\",\"n\":\""
                + unsigned(key.getModulus()) + "\",\"e\":\"" + unsigned(key.getPublicExponent()) + "\"}]}";
        String discovery = "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + KEYS + "\"}";
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getRawPath();
                if (!"GET".equals(exchange.getRequestMethod()) || exchange.getRequestURI().getRawQuery() != null
                        || !(DISCOVERY.equals(path) || KEYS.equals(path))) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = (DISCOVERY.equals(path) ? discovery : jwks).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    private static String unsigned(BigInteger number) {
        byte[] bytes = number.toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
