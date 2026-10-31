package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static com.mongodb.client.model.Updates.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import com.neurovision.rehab.analytics.infrastructure.integration.AnalyticsKafkaMongoTestResource;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoStreamSummaryProjectionAdapter;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Opt-in JVM HTTP coverage. Assertions are intentionally not substitutes for future runtime evidence. */
@QuarkusTest
@TestProfile(AnalyticsSessionStreamsHttpProfile.class)
@Execution(ExecutionMode.SAME_THREAD)
class AnalyticsSessionStreamsHttpIT {
    private static final String SESSION = ClinicalHeatmapAuthorizationStubResource.ALLOWED_SESSION;
    private static final String EMPTY_SESSION = "91000000-0000-0000-0000-000000000001";
    private static final String FOREIGN_SESSION = "91000000-0000-0000-0000-000000000002";
    private static final String DENIED_SESSION = "91000000-0000-0000-0000-000000000003";
    private static final String UNKNOWN_SESSION = "91000000-0000-0000-0000-000000000004";
    // The last UUID sorts before the others under signed UUID ordering, but after them as canonical strings.
    private static final String FIRST = "00000000-0000-0000-0000-00000000000a";
    private static final String SECOND = "70000000-0000-0000-0000-00000000000b";
    private static final String THIRD = "e0000000-0000-0000-0000-00000000000c";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final List<Expected> ALL = List.of(
            new Expected(FIRST, 2, 1, 1), new Expected(SECOND, 4, 2, 0), new Expected(THIRD, 1, 0, 0));

    @Inject ReactiveMongoClient mongo;
    @Inject MongoStreamSummaryProjectionAdapter projections;
    @Inject ObjectMapper mapper;
    @Inject @ConfigProperty(name = "quarkus.mongodb.database") String database;
    @Inject @ConfigProperty(name = "analytics.summary.collection") String summaries;
    @Inject @ConfigProperty(name = "analytics.clinical.base-url") URI clinicalBase;
    @TestHTTPResource URI analyticsBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String doctor;

    @BeforeEach
    void seedOnlyTheDedicatedSyntheticDatabase() throws Exception {
        AnalyticsKafkaMongoTestResource.requireStreamDiscoveryOptIn();
        assertTrue(database.matches("analytics_streams_it_[0-9a-f]{32}"), "Dedicated test database required");
        collection().deleteMany(new Document()).await().atMost(WAIT);
        // Persist via the production public writer, deliberately out of order; never publish Kafka events.
        store(emptySummary(SESSION, THIRD), 1);
        store(summary(SESSION, FIRST, 1, true), 2);
        store(summary(SESSION, SECOND, 2, false), 4);
        store(summary(FOREIGN_SESSION, FIRST, 1, true), 1);
        store(summary(DENIED_SESSION, FIRST, 1, true), 1);
        var otherVersion = collection().find(new Document("therapySessionId", SESSION)
                .append("streamId", FIRST)).collect().first().await().atMost(WAIT);
        otherVersion.remove("_id");
        otherVersion.put("calculationVersion", "future-test-only");
        collection().insertOne(otherVersion).await().atMost(WAIT);
        doctor = token(List.of("DOCTOR"), false);
        control("reset", null);
        control("permit", SESSION);
        control("permit", EMPTY_SESSION);
    }

    @Test
    void pagesCanonicalObservedStreamsWithExactCountsRevisionAndDtoShape() throws Exception {
        assertPage(get(SESSION, "?page=0&size=2", doctor), SESSION, 0, 2, true, ALL.subList(0, 2));
        assertPage(get(SESSION, "?page=1&size=2", doctor), SESSION, 1, 2, false, ALL.subList(2, 3));
        assertPage(get(SESSION, "?page=2&size=2", doctor), SESSION, 2, 2, false, List.of());
        assertClinical(3);
    }

    @Test
    void acceptsDefaultsMaximumSizeAndMaximumOffsetWithoutTotals() throws Exception {
        assertPage(get(SESSION, "", doctor), SESSION, 0, 20, false, ALL);
        assertPage(get(SESSION, "?size=50", doctor), SESSION, 0, 50, false, ALL);
        assertPage(get(SESSION, "?page=20&size=50", doctor), SESSION, 20, 50, false, List.of());
        assertClinical(3);
    }

    @Test
    void returnsEmptyOnlyAfterClinicalPermitsTheSession() throws Exception {
        assertPage(get(EMPTY_SESSION, "?size=2", doctor), EMPTY_SESSION, 0, 2, false, List.of());
        assertClinical(1);
    }

    @Test
    void rejectsInvalidAndOverflowingBoundsWithoutCallingClinical() throws Exception {
        for (var query : List.of("?page=-1", "?size=0", "?size=51", "?page=1.5", "?page=abc",
                "?size=%20", "?page=2147483648", "?size=2147483648", "?page=99999999999999999999",
                "?page=21&size=50", "?page=2147483647&size=50")) {
            assertError(get(SESSION, query, doctor), 400, "STREAM_DISCOVERY_INVALID_REQUEST");
        }
        assertError(get("invalid-session", "", doctor), 400, "STREAM_DISCOVERY_INVALID_REQUEST");
        assertClinical(0);
    }

    @Test
    void missingCredentialsHaveFixedUnauthenticatedJsonAndNoStore() throws Exception {
        var response = get(SESSION, "", null);
        assertError(response, 401, "UNAUTHENTICATED");
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"),
                "Authentication rejection must preserve the bearer challenge");
        assertClinical(0);
    }

    @Test
    void invalidCredentialsHaveFixedUnauthenticatedJsonAndNoStore() throws Exception {
        var response = get(SESSION, "", "not-a-jwt");
        assertError(response, 401, "UNAUTHENTICATED");
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"),
                "Authentication rejection must preserve the bearer challenge");
        assertClinical(0);
    }

    @Test
    void expiredCredentialsHaveFixedUnauthenticatedJsonAndNoStore() throws Exception {
        var response = get(SESSION, "", token(List.of("DOCTOR"), true));
        assertError(response, 401, "UNAUTHENTICATED");
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"),
                "Authentication rejection must preserve the bearer challenge");
        assertClinical(0);
    }

    @Test
    void patientRolesHaveFixedForbiddenJsonAndNoStore() throws Exception {
        assertError(get(SESSION, "", token(List.of("PATIENT"), false)), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(0);
    }

    @Test
    void adminRolesHaveFixedForbiddenJsonAndNoStore() throws Exception {
        assertError(get(SESSION, "", token(List.of("ADMIN"), false)), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(0);
    }

    @Test
    void doctorPatientRolesHaveFixedForbiddenJsonAndNoStore() throws Exception {
        assertError(get(SESSION, "", token(List.of("DOCTOR", "PATIENT"), false)), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(0);
    }

    @Test
    void doctorAdminRolesHaveFixedForbiddenJsonAndNoStore() throws Exception {
        assertError(get(SESSION, "", token(List.of("DOCTOR", "ADMIN"), false)), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(0);
    }

    @Test
    void patientAdminRolesHaveFixedForbiddenJsonAndNoStore() throws Exception {
        assertError(get(SESSION, "", token(List.of("PATIENT", "ADMIN"), false)), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(0);
    }

    @Test
    void unknownAndDeniedSessionsHaveTheSameGenericForbiddenResponse() throws Exception {
        assertError(get(DENIED_SESSION, "", doctor), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertError(get(UNKNOWN_SESSION, "", doctor), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(2);
    }

    @Test
    void reauthorizesTheOriginalBearerOnTheNextPageAfterRevocation() throws Exception {
        assertPage(get(SESSION, "?size=2", doctor), SESSION, 0, 2, true, ALL.subList(0, 2));
        control("deny", SESSION);
        // Corrupt data cannot turn a revoked page into a MongoDB error or reveal projection metadata.
        corrupt(THIRD, "summary.highSequence", 99L);
        assertError(get(SESSION, "?page=1&size=2", doctor), 403, "STREAM_DISCOVERY_FORBIDDEN");
        assertClinical(2);
    }

    @Test
    void malformedSelectedSummaryFailsClosedWithoutRepairingData() throws Exception {
        corrupt(FIRST, "summary.highSequence", 99L);
        assertError(get(SESSION, "?size=2", doctor), 503, "STREAM_DISCOVERY_UNAVAILABLE");
        assertClinical(1);
    }

    @Test
    void malformedLookaheadHeatmapFailsClosedInsteadOfBeingSilentlyOmitted() throws Exception {
        corrupt(THIRD, "summary.heatmap.cellCounts", Collections.nCopies(100, 1L));
        assertError(get(SESSION, "?size=2", doctor), 503, "STREAM_DISCOVERY_UNAVAILABLE");
        assertClinical(1);
    }

    private HttpResponse<String> get(String session, String query, String bearer) throws Exception {
        var before = snapshot();
        var request = HttpRequest.newBuilder(analyticsBase.resolve(
                "/api/v1/analytics/sessions/" + session + "/streams" + query)).timeout(WAIT).GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        // Compare all fixture collections, including IDs/revisions/payloads; never print their contents.
        assertTrue(before.equals(snapshot()), "Discovery GET must not modify fixture documents or collections");
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return response;
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "Errors must carry JSON, including framework authentication and authorization rejections");
        assertEquals(mapper.valueToTree(Map.of("code", code)), mapper.readTree(response.body()));
    }

    private void assertPage(HttpResponse<String> response, String session, int page, int size,
            boolean hasNext, List<Expected> expected) throws Exception {
        assertEquals(200, response.statusCode());
        var body = mapper.readTree(response.body());
        assertEquals(Set.of("therapySessionId", "page", "size", "hasNext", "items"), fields(body));
        assertEquals(session, body.path("therapySessionId").asText());
        assertEquals(page, body.path("page").asInt());
        assertEquals(size, body.path("size").asInt());
        assertEquals(hasNext, body.path("hasNext").asBoolean());
        var items = body.path("items");
        assertTrue(items.isArray());
        assertEquals(expected.size(), items.size());
        for (int i = 0; i < expected.size(); i++) {
            var item = items.get(i);
            var value = expected.get(i);
            assertEquals(Set.of("streamId", "calculationVersion", "revision", "sampleCounts"), fields(item));
            assertEquals(value.stream(), item.path("streamId").asText());
            assertEquals("baseline-v1", item.path("calculationVersion").asText());
            assertEquals(value.revision(), item.path("revision").asLong());
            var counts = item.path("sampleCounts");
            assertEquals(Set.of("gaze", "gameplay", "total"), fields(counts));
            assertEquals(value.gaze(), counts.path("gaze").asLong());
            assertEquals(value.gameplay(), counts.path("gameplay").asLong());
            assertEquals(value.gaze() + value.gameplay(), counts.path("total").asLong());
        }
    }

    private Map<String, List<String>> snapshot() {
        var db = mongo.getDatabase(database);
        var documents = new HashMap<String, List<String>>();
        for (String name : db.listCollectionNames().collect().asList().await().atMost(WAIT)) {
            documents.put(name, db.getCollection(name).find().collect().asList().await().atMost(WAIT).stream()
                    .map(Document::toJson).sorted().toList());
        }
        return documents;
    }

    private void control(String operation, String session) throws Exception {
        var request = HttpRequest.newBuilder(clinicalBase.resolve("/test-stream-discovery/" + operation))
                .timeout(WAIT).header("Authorization", "Bearer " + doctor)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (session != null) request.header("X-Test-Session", session);
        assertEquals(200, http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private void assertClinical(int expectedRequests) throws Exception {
        var response = http.send(HttpRequest.newBuilder(clinicalBase.resolve("/test-stream-discovery/observed"))
                .timeout(WAIT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var observed = mapper.readTree(response.body());
        assertEquals(expectedRequests, observed.path("requests").asInt());
        assertTrue(observed.path("originalBearerMatches").asBoolean(), "Clinical must receive the original bearer");
    }

    private void store(BaselineStreamSummary summary, long revision) {
        projections.storeIfNewer(new StreamSummaryProjection(AnalyticsProjectionIdentity.from(summary), revision, summary))
                .await().atMost(WAIT);
    }

    private void corrupt(String stream, String field, Object value) {
        collection().updateOne(new Document("therapySessionId", SESSION).append("streamId", stream)
                .append("calculationVersion", "baseline-v1"), set(field, value)).await().atMost(WAIT);
    }

    private ReactiveMongoCollection<Document> collection() { return mongo.getDatabase(database).getCollection(summaries); }

    private static Set<String> fields(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static BaselineStreamSummary summary(String session, String stream, int gazeCount, boolean gameplay) {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var gaze = java.util.stream.IntStream.range(0, gazeCount)
                .mapToObj(i -> new AcceptedTelemetryBatch.GazeSample(i, .2, .3, .9)).toList();
        var accepted = new AcceptedTelemetryBatch(1, UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(session),
                UUID.fromString(stream), 0, time, time.plusMillis(10), time.plusMillis(20), UUID.randomUUID(), null,
                gaze, gameplay ? List.of(new AcceptedTelemetryBatch.GameplaySample(0, .1, .2, .3, .4, 1, 1.5, .6))
                        : List.of(), "synthetic");
        return new BaselineTelemetryMetricsCalculator().calculate(
                List.of(new AdmittedTelemetryBatch(accepted, TelemetryAdmissionQuality.INITIAL)));
    }

    private static BaselineStreamSummary emptySummary(String session, String stream) {
        var profile = AnalyticsCalculationProfile.BASELINE_V1;
        var numeric = BaselineStreamSummary.NumericSummary.empty();
        var ordered = BaselineStreamSummary.OrderedNumericSummary.empty();
        return new BaselineStreamSummary(profile, 1, UUID.fromString(session), UUID.fromString(stream),
                0, 0, List.of(), 0, true, Optional.empty(), new BaselineStreamSummary.SampleCounts(0, 0),
                numeric, Collections.nCopies(profile.confidenceBinCount(), 0L),
                new BaselineStreamSummary.Heatmap(profile.gridRows(), profile.gridColumns(),
                        Collections.nCopies(profile.gridRows() * profile.gridColumns(), 0L)),
                new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0),
                new BaselineStreamSummary.PathMetrics(0, numeric, 0),
                new BaselineStreamSummary.GameplayMetrics(numeric, ordered, ordered, ordered));
    }

    private static String token(List<String> groups, boolean expired) {
        long now = Instant.now().getEpochSecond();
        return Jwt.subject("synthetic-subject").issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access").claim("cognito:groups", groups)
                .issuedAt(now - 600).expiresAt(expired ? now - 300 : now + 300).sign();
    }

    private record Expected(String stream, long revision, long gaze, long gameplay) {}
}
