package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.interfaces.rest.resource.SessionReportPeersTestResource.VisualSearchScenario.*;

import com.neurovision.rehab.clinical.interfaces.rest.resource.SessionReportPeersTestResource.VisualSearchScenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionReportRestAssembler;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.junit.jupiter.api.*;

/** Prepared opt-in HTTP/CDI checks. Compilation is not evidence that these runtime cases pass. */
@QuarkusTest
@TestProfile(ClinicalSessionReportHttpProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class DoctorSessionReportHttpIT {
    private static final Instant T = Instant.parse("2026-10-22T00:00:00Z");
    private static final CognitoSubject DOCTOR = new CognitoSubject("synthetic-report-http-doctor");
    private static final CognitoSubject OTHER = new CognitoSubject("synthetic-report-http-other");
    private static final ClinicalPatientId PATIENT = new ClinicalPatientId(new UUID(21, 1));
    private static final ClinicalPatientId REVOKED = new ClinicalPatientId(new UUID(21, 2));
    private static final ClinicalPatientId SNAPSHOT_REVOKED = new ClinicalPatientId(new UUID(21, 3));
    private static final UUID OWNED_SESSION = new UUID(22, 1);
    private static final UUID FOREIGN_SESSION = new UUID(22, 2);
    private static final UUID REVOKED_SESSION = new UUID(22, 3);
    private static final UUID SNAPSHOT_REVOKED_SESSION = new UUID(22, 4);
    private static final UUID LIST_SESSION = new UUID(22, 5);
    private static final UUID EMPTY_LIST_SESSION = new UUID(22, 6);
    private static final UUID VISUAL_SEARCH_SESSION = new UUID(22, 7);
    private static final UUID FOREIGN_VISUAL_SEARCH_SESSION = new UUID(22, 8);
    private static final ClinicalPatientId VISUAL_SEARCH_REVOKED = new ClinicalPatientId(new UUID(21, 4));
    private static final UUID VISUAL_SEARCH_REVOKED_SESSION = new UUID(22, 9);
    private static final UUID VISUAL_SEARCH_CONFIGURATION = new UUID(25, 7);
    private static final String SNAPSHOT_TABLE = "session_report_snapshots";
    private static final String NOTICE = "Sin evidencia analítica disponible";

    @Inject AgroalDataSource dataSource;
    @Inject Flyway flyway;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject ComposeSessionReportService reports;
    @Inject SessionReportSnapshotRepository savedReports;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URI http;
    // Injected by the test resource, not a replacement CDI application bean.
    SessionReportPeersTestResource peers;
    private Map<String, List<?>> before;
    private String accessToken;
    private SessionReportSnapshotRepository.Snapshot heatmapCopy;

    @BeforeAll
    void attestActualDatabaseBeforeManualMigrationAndCommittedSyntheticFixtures() throws SQLException {
        ClinicalSessionReportHttpProfile.requireOptIn();
        assertNotNull(reports, "Production report composition must resolve through CDI");
        assertEquals("http", http.getScheme());
        assertEquals("127.0.0.1", http.getHost(), "HTTP requests must stay on loopback");
        // No Flyway or seed can run before actual database/user attestation succeeds.
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT current_database(), current_user, current_setting('server_version_num')")) {
            statement.setQueryTimeout(5);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next(), "Dedicated PostgreSQL identity unavailable");
                assertTrue(ClinicalSessionReportHttpProfile.DATABASE.equals(row.getString(1))
                        && ClinicalSessionReportHttpProfile.USER.equals(row.getString(2)),
                        "Refusing a non-dedicated database or role");
                int version = Integer.parseInt(row.getString(3));
                assertTrue(version >= 180000 && version < 190000, "Isolated PostgreSQL 18 required");
            }
        }
        flyway.migrate();
        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(snapshotInTransaction().values().stream().allMatch(List::isEmpty),
                    "Refusing a nonempty synthetic database; this suite never cleans it");
            seedPatient(PATIENT, 1);
            seedPatient(REVOKED, 2);
            seedPatient(SNAPSHOT_REVOKED, 3);
            seedSession(PATIENT, DOCTOR, OWNED_SESSION, 1);
            seedSession(PATIENT, OTHER, FOREIGN_SESSION, 2);
            seedSession(REVOKED, DOCTOR, REVOKED_SESSION, 3);
            seedSession(SNAPSHOT_REVOKED, DOCTOR, SNAPSHOT_REVOKED_SESSION, 4);
        seedSession(PATIENT, DOCTOR, LIST_SESSION, 5);
        seedSession(PATIENT, DOCTOR, EMPTY_LIST_SESSION, 6);
            var visualSearch = TherapeuticConfigurationTestData.visualSearchSnapshot().parameters();
            seedSession(PATIENT, DOCTOR, VISUAL_SEARCH_SESSION, 7, visualSearch);
            seedSession(PATIENT, OTHER, FOREIGN_VISUAL_SEARCH_SESSION, 8, visualSearch);
            seedPatient(VISUAL_SEARCH_REVOKED, 4);
            seedSession(VISUAL_SEARCH_REVOKED, DOCTOR, VISUAL_SEARCH_REVOKED_SESSION, 9, visualSearch);
            entities.flush();
        });
        seedHeatmapCopy();
    }

    private void seedHeatmapCopy() {
        // Seed through the real writer only after the dedicated database and committed sessions are attested.
        String bearer = token(List.of("DOCTOR"), Instant.now().plusSeconds(300));
        peers.scenario(PATIENT.value(), OWNED_SESSION, bearer, false, false);
        var base = QuarkusTransaction.requiringNew().call(
                () -> reports.compose(DOCTOR.value(), bearer, PATIENT.value(), OWNED_SESSION));
        var candidate = frozenHeatmapCopy(base, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), mapper);
        var prior = snapshot();
        var result = savedReports.insertAuthorized(DOCTOR.value(), PATIENT.value(), OWNED_SESSION,
                "http-pdf-heatmap-seed", candidate);
        assertTrue(result.created(), "Fresh isolated fixture must insert its one saved copy");
        assertEquals(candidate, result.snapshot(), "The writer must preserve the exact synthetic saved copy");
        var after = snapshot();
        assertEquals(prior.keySet(), after.keySet());
        for (String table : prior.keySet()) if (!table.equals(SNAPSHOT_TABLE))
            assertTrue(prior.get(table).equals(after.get(table)), "Heatmap fixture changed another application table");
        assertTrue(prior.get(SNAPSHOT_TABLE).isEmpty() && after.get(SNAPSHOT_TABLE).size() == 1,
                "Heatmap setup must add exactly one copy to the empty fixture");
        heatmapCopy = result.snapshot();
    }

    private static SessionReportSnapshotRepository.Snapshot frozenHeatmapCopy(SessionReportV1 base, Instant createdAt, ObjectMapper mapper) {
        UUID session = base.execution().therapySessionId();
        var source = new Source(session, new UUID(27, 1), "baseline-v1", 1, 1, 1);
        var complete = new Completeness(1, 3, 3, 1, false, List.of(new Range(2, 2)), 0, true,
                new TimeRange(T, T), new Counts(10, 0, 10));
        var cells = new ArrayList<>(Collections.nCopies(100, 0L));
        cells.set(0, 4L); cells.set(9, 2L); cells.set(90, 1L); cells.set(99, 3L);
        var grid = new Grid(10, 10, cells);
        var histogram = new ArrayList<>(Collections.nCopies(10, 0L)); histogram.set(5, 10L);
        var empty = new Numeric(0, null, null, null); var ordered = new Ordered(empty, null, null, null);
        var summary = new Summary(source, complete, new Numeric(10, .5, .5, .5), histogram, grid,
                new Coverage(new BoundingBox(0, 0, 1, 1, 1, 1, 1), 4, .04),
                new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.Path(0, empty, 0),
                new Gameplay(empty, ordered, ordered, ordered));
        var stream = new Stream(summary,
                new Heatmap(source, complete, grid, "NORMALIZED_ZERO_TO_ONE", "RAW_GAZE_SAMPLE_COUNT", false),
                new GazePage(source, complete, 100, 64, true, true,
                        List.of(new GazePoint(1, 0, T, .1, .2, .5, true),
                                new GazePoint(1, 1, T, .4, .5, .5, false),
                                new GazePoint(3, 0, T, .8, .9, .5, true),
                                new GazePoint(3, 1, T, .9, .1, .5, false))));
        var report = new SessionReportV1(base.clinicalPatientId(), base.doctor(), base.patient(), base.execution(),
                base.configuration(), base.sessionLimits(), base.technicalProgress(),
                new ObservedEvidence(session, 0, 20, false, List.of(stream)), base.issuedAt());
        return new SessionReportSnapshotRepository.Snapshot(new UUID(26, 1000), base.clinicalPatientId(), session, createdAt,
                SessionReportRestAssembler.toResponse(report, mapper).representation().toString());
    }

    @BeforeEach
    void prepareRequestObservation() {
        accessToken = token(List.of("DOCTOR"), Instant.now().plusSeconds(300));
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, false, false);
        before = snapshot();
    }

    @AfterEach
    void everyRequestLeavesPersistedStateUnchanged() {
        assertTrue(before.equals(snapshot()), "HTTP report request changed persisted Clinical data");
    }

    @Test
    void completedOwnedReportUsesRealCdiAdaptersAndConfirmedAbsence() throws Exception {
        var response = request(accessToken, PATIENT, OWNED_SESSION);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        var json = mapper.readTree(response.asString());
        assertEquals(1, json.path("schemaVersion").asInt());
        assertTrue(PATIENT.value().toString().equals(json.path("clinicalPatientId").asText()), "Wrong report patient");
        assertTrue(OWNED_SESSION.toString().equals(json.path("execution").path("therapySessionId").asText()),
                "Wrong report session");
        assertEquals("COMPLETED", json.path("execution").path("status").asText());
        assertEquals(1, json.path("configuration").path("configurationVersion").asLong());
        assertEquals("AVAILABLE", json.path("patient").path("availability").asText());
        assertEquals("Synthetic", json.path("patient").path("firstName").asText());
        assertEquals("Patient", json.path("patient").path("lastName").asText());
        assertEquals("AVAILABLE", json.path("doctor").path("availability").asText());
        assertEquals("Synthetic", json.path("doctor").path("firstName").asText());
        assertEquals("Doctor", json.path("doctor").path("lastName").asText());
        assertEquals("CONFIRMED_NO_EVIDENCE", json.path("analytics").path("availability").asText());
        assertTrue(json.path("analytics").path("streams").isArray());
        assertEquals(0, json.path("analytics").path("streams").size());
        assertEquals(NOTICE, json.path("analyticsNotice").asText());
        assertFalse(response.asString().contains(accessToken), "Response must not contain a bearer token");
        assertFalse(response.asString().contains(DOCTOR.value()), "Response must not contain a Cognito subject");
        counts(1, 1, 1);
    }

    @Test void missingTokenIs401WithBearerChallenge() {
        frameworkUnauthenticated(request(null, PATIENT, OWNED_SESSION));
    }

    @Test void malformedTokenIs401WithBearerChallenge() {
        frameworkUnauthenticated(request("not-a-jwt", PATIENT, OWNED_SESSION));
    }

    @Test void tamperedSignatureIs401WithBearerChallenge() {
        String tampered = accessToken.substring(0, accessToken.lastIndexOf('.') + 1) + "bad-signature";
        frameworkUnauthenticated(request(tampered, PATIENT, OWNED_SESSION));
    }

    @Test void expiredTokenIs401WithBearerChallenge() {
        frameworkUnauthenticated(request(token(List.of("DOCTOR"), Instant.now().minusSeconds(120)), PATIENT, OWNED_SESSION));
    }

    @Test void patientRoleIs403BeforeEnrichment() {
        frameworkForbidden(request(token(List.of("PATIENT"), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION));
    }

    @Test void adminRoleIs403BeforeEnrichment() {
        frameworkForbidden(request(token(List.of("ADMIN"), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION));
    }

    @Test void mixedDoctorPatientRoleIs403BeforeEnrichment() throws Exception {
        resourceError(request(token(List.of("DOCTOR", "PATIENT"), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION),
                403, "SESSION_REPORT_FORBIDDEN");
        counts(0, 0, 0);
    }

    @Test void mixedDoctorAdminRoleIs403BeforeEnrichment() throws Exception {
        resourceError(request(token(List.of("DOCTOR", "ADMIN"), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION),
                403, "SESSION_REPORT_FORBIDDEN");
        counts(0, 0, 0);
    }

    @Test void unknownSessionIs403WithoutExternalReads() throws Exception {
        resourceError(request(accessToken, PATIENT, new UUID(22, 99)), 403, "SESSION_REPORT_FORBIDDEN");
        counts(0, 0, 0);
    }

    @Test void anotherDoctorsSessionIs403WithoutExternalReads() throws Exception {
        resourceError(request(accessToken, PATIENT, FOREIGN_SESSION), 403, "SESSION_REPORT_FORBIDDEN");
        counts(0, 0, 0);
    }

    @Test void committedAssignmentRevocationIs403WithoutExternalReads() throws Exception {
        revokeAssignment(REVOKED);
        peers.scenario(REVOKED.value(), REVOKED_SESSION, accessToken, false, false);
        resourceError(request(accessToken, REVOKED, REVOKED_SESSION), 403, "SESSION_REPORT_FORBIDDEN");
        counts(0, 0, 0);
    }

    @Test void unavailableProfileIsGeneric503NeverAnalyticalAbsence() throws Exception {
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, false);
        resourceError(request(accessToken, PATIENT, OWNED_SESSION), 503, "SESSION_REPORT_UNAVAILABLE");
        counts(1, 1, 0);
    }

    @Test void unavailableAnalyticsIsGeneric503NeverAnalyticalAbsence() throws Exception {
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, false, true);
        resourceError(request(accessToken, PATIENT, OWNED_SESSION), 503, "SESSION_REPORT_UNAVAILABLE");
        counts(1, 1, 1);
    }

    @Test void snapshotCreationAndClinicalOnlyGetReturnTheExactFrozenCopy() throws Exception {
        var copy = createCopy(PATIENT, OWNED_SESSION, "http-snapshot-create");
        counts(1, 1, 1);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        getCopy(PATIENT, OWNED_SESSION, copy);
        counts(0, 0, 0);
    }

    @Test void snapshotSameKeyReplaySkipsUnavailableEnrichment() throws Exception {
        String key = "http-snapshot-replay";
        var copy = createCopy(PATIENT, OWNED_SESSION, key);
        counts(1, 1, 1);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        var replay = postSnapshot(accessToken, PATIENT, OWNED_SESSION, key);
        assertUnchanged();
        assertEquals(200, replay.statusCode());
        assertEquals("no-store", replay.header("Cache-Control"));
        assertTrue(copy.equals(mapper.readTree(replay.asString())), "Replay changed the frozen copy");
        counts(0, 0, 0);
    }

    @Test void snapshotNewKeyCreatesAnotherCopyAndPreservesBoth() throws Exception {
        var first = createCopy(PATIENT, OWNED_SESSION, "http-snapshot-first-key");
        counts(1, 1, 1);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, false, false);
        var second = createCopy(PATIENT, OWNED_SESSION, "http-snapshot-second-key");
        assertFalse(first.path("reportSnapshotId").equals(second.path("reportSnapshotId")),
                "A new key must create a separate copy");
        counts(1, 1, 1);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        getCopy(PATIENT, OWNED_SESSION, first);
        counts(0, 0, 0);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        getCopy(PATIENT, OWNED_SESSION, second);
        counts(0, 0, 0);
    }

    @Test void snapshotListPagesContainOnlyOrderedMetadataWithoutEnrichment() throws Exception {
        peers.scenario(PATIENT.value(), LIST_SESSION, accessToken, false, false);
        var first = createCopy(PATIENT, LIST_SESSION, "http-list-first");
        peers.scenario(PATIENT.value(), LIST_SESSION, accessToken, false, false);
        var second = createCopy(PATIENT, LIST_SESSION, "http-list-second");
        var expected = new ArrayList<>(List.of(first, second));
        expected.sort(Comparator.<JsonNode, Instant>comparing(copy -> Instant.parse(copy.path("createdAt").asText()))
                .thenComparing(copy -> copy.path("reportSnapshotId").asText()).reversed());
        peers.scenario(PATIENT.value(), LIST_SESSION, accessToken, true, true);
        for (int page = 0; page < 3; page++) {
            var body = listPage(PATIENT, LIST_SESSION, "page=" + page + "&size=1", page, 1, 2);
            assertEquals(page < 2 ? 1 : 0, body.path("snapshots").size());
            if (page < 2) assertSummary(expected.get(page), body.path("snapshots").get(0));
        }
        var defaults = listPage(PATIENT, LIST_SESSION, "", 0, 20, 2);
        var maximum = listPage(PATIENT, LIST_SESSION, "size=100", 0, 100, 2);
        assertEquals(2, defaults.path("snapshots").size());
        assertEquals(defaults.path("snapshots"), maximum.path("snapshots"));
        for (int index = 0; index < 2; index++) assertSummary(expected.get(index), defaults.path("snapshots").get(index));
        getCopy(PATIENT, LIST_SESSION, first);
        getCopy(PATIENT, LIST_SESSION, second);
        counts(0, 0, 0);
    }

    @Test void snapshotListAuthorizedEmptyAndDistantPageRemainSuccessful() throws Exception {
        peers.scenario(PATIENT.value(), EMPTY_LIST_SESSION, accessToken, true, true);
        assertTrue(listPage(PATIENT, EMPTY_LIST_SESSION, "", 0, 20, 0).path("snapshots").isEmpty());
        assertTrue(listPage(PATIENT, EMPTY_LIST_SESSION, "page=100&size=1", 100, 1, 0).path("snapshots").isEmpty());
    }

    @Test void snapshotListRejectsInvalidTokensAndNonPureDoctorRoles() throws Exception {
        String tampered = accessToken.substring(0, accessToken.lastIndexOf('.') + 1) + "bad-signature";
        for (String invalid : Arrays.asList(null, "not-a-jwt", tampered,
                token(List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            frameworkUnauthenticated(listSnapshots(invalid, PATIENT, LIST_SESSION, ""));
            assertUnchanged();
        }
        for (String role : List.of("PATIENT", "ADMIN")) {
            frameworkForbidden(listSnapshots(token(List.of(role), Instant.now().plusSeconds(300)), PATIENT, LIST_SESSION, ""));
            assertUnchanged();
            snapshotError(listSnapshots(token(List.of("DOCTOR", role), Instant.now().plusSeconds(300)),
                    PATIENT, LIST_SESSION, ""), 403, "FORBIDDEN");
        }
    }

    @Test void snapshotListDeniesUnknownForeignAndWrongPatientScopes() throws Exception {
        for (UUID session : List.of(new UUID(22, 99), FOREIGN_SESSION))
            snapshotError(listSnapshots(accessToken, PATIENT, session, ""), 403, "FORBIDDEN");
        snapshotError(listSnapshots(accessToken, REVOKED, LIST_SESSION, ""), 403, "FORBIDDEN");
    }

    @Test void snapshotListRejectsInvalidPaginationWithoutReadingPeersOrWriting() throws Exception {
        for (String query : List.of("page=-1", "page=bad", "size=0", "size=101", "size=bad",
                "page=2147483648", "page=2147483647&size=2"))
            snapshotError(listSnapshots(accessToken, PATIENT, LIST_SESSION, query), 400, "INVALID_REQUEST");
    }

    @Test void snapshotPostAndGetRejectMissingMalformedTamperedAndExpiredTokens() {
        String tampered = accessToken.substring(0, accessToken.lastIndexOf('.') + 1) + "bad-signature";
        String[] tokens = {null, "not-a-jwt", tampered,
                token(List.of("DOCTOR"), Instant.now().minusSeconds(120))};
        for (String invalid : tokens) {
            var post = postSnapshot(invalid, PATIENT, OWNED_SESSION, "http-snapshot-unauthenticated");
            assertUnchanged();
            frameworkUnauthenticated(post);
            var get = getSnapshot(invalid, PATIENT, OWNED_SESSION, new UUID(26, 99));
            assertUnchanged();
            frameworkUnauthenticated(get);
        }
    }

    @Test void snapshotPostAndGetRejectPatientAndAdminRolesBeforeEnrichment() {
        for (String role : List.of("PATIENT", "ADMIN")) {
            String bearer = token(List.of(role), Instant.now().plusSeconds(300));
            var post = postSnapshot(bearer, PATIENT, OWNED_SESSION, "http-snapshot-wrong-role");
            assertUnchanged();
            frameworkForbidden(post);
            var get = getSnapshot(bearer, PATIENT, OWNED_SESSION, new UUID(26, 99));
            assertUnchanged();
            frameworkForbidden(get);
        }
    }

    @Test void snapshotPostAndGetRequirePureDoctorRole() throws Exception {
        for (String otherRole : List.of("PATIENT", "ADMIN")) {
            String bearer = token(List.of("DOCTOR", otherRole), Instant.now().plusSeconds(300));
            snapshotError(postSnapshot(bearer, PATIENT, OWNED_SESSION, "http-snapshot-mixed-role"),
                    403, "FORBIDDEN");
            snapshotError(getSnapshot(bearer, PATIENT, OWNED_SESSION, new UUID(26, 99)), 403, "FORBIDDEN");
        }
    }

    @Test void snapshotPostAndGetDenyUnknownAndForeignSessionsBeforeLookup() throws Exception {
        for (UUID session : List.of(new UUID(22, 99), FOREIGN_SESSION)) {
            snapshotError(postSnapshot(accessToken, PATIENT, session, "http-snapshot-denied-scope"),
                    403, "FORBIDDEN");
            snapshotError(getSnapshot(accessToken, PATIENT, session, new UUID(26, 99)), 403, "FORBIDDEN");
        }
    }

    @Test void snapshotRevocationBlocksGetReplayAndNewKeyWithoutChangingTheCopy() throws Exception {
        String key = "http-snapshot-revoked";
        peers.scenario(SNAPSHOT_REVOKED.value(), SNAPSHOT_REVOKED_SESSION, accessToken, false, false);
        var copy = createCopy(SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION, key);
        counts(1, 1, 1);
        revokeAssignment(SNAPSHOT_REVOKED);
        peers.scenario(SNAPSHOT_REVOKED.value(), SNAPSHOT_REVOKED_SESSION, accessToken, true, true);
        UUID id = UUID.fromString(copy.path("reportSnapshotId").asText());
        snapshotError(getSnapshot(accessToken, SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION, id), 403, "FORBIDDEN");
        snapshotError(listSnapshots(accessToken, SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION, ""), 403, "FORBIDDEN");
        pdfError(getPdf(accessToken, SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION, id.toString()), 403, "FORBIDDEN");
        snapshotError(postSnapshot(accessToken, SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION, key), 403, "FORBIDDEN");
        snapshotError(postSnapshot(accessToken, SNAPSHOT_REVOKED, SNAPSHOT_REVOKED_SESSION,
                "http-snapshot-revoked-new-key"), 403, "FORBIDDEN");
    }

    @Test void snapshotAuthorizedMissingCopyIs404WithoutEnrichment() throws Exception {
        snapshotError(getSnapshot(accessToken, PATIENT, OWNED_SESSION, new UUID(26, 99)), 404, "NOT_FOUND");
    }

    @Test void snapshotDependencyFailuresReturnGeneric503WithoutPartialPersistence() throws Exception {
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, false);
        var profileFailure = postSnapshot(accessToken, PATIENT, OWNED_SESSION, "http-snapshot-profile-failure");
        assertUnchanged();
        resourceError(profileFailure, 503, "SESSION_REPORT_SNAPSHOT_UNAVAILABLE");
        counts(1, 1, 0);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, false, true);
        var analyticsFailure = postSnapshot(accessToken, PATIENT, OWNED_SESSION, "http-snapshot-analytics-failure");
        assertUnchanged();
        resourceError(analyticsFailure, 503, "SESSION_REPORT_SNAPSHOT_UNAVAILABLE");
        counts(1, 1, 1);
    }

    @Test void pdfDownloadUsesRealCdiRendererAndFrozenCopyWithoutEnrichment() throws Exception {
        var copy = createCopy(PATIENT, OWNED_SESSION, "http-pdf-frozen");
        counts(1, 1, 1);
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        String id = copy.path("reportSnapshotId").asText();
        var response = getPdf(accessToken, PATIENT, OWNED_SESSION, id);
        assertUnchanged();
        assertEquals(200, response.statusCode());
        assertEquals("application/pdf", response.contentType());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(("attachment; filename=\"session-report-" + id + ".pdf\"").equals(response.header("Content-Disposition")),
                "PDF filename must contain only the saved copy reference");
        byte[] bytes = response.asByteArray();
        assertTrue(bytes.length > 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-',
                "Successful download must contain a PDF document");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() > 0, "PDF requires readable pages");
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s", "");
            assertTrue(text.contains(id), "PDF must identify the saved copy");
            assertTrue(text.contains(copy.path("createdAt").asText()), "PDF must retain the copy creation time");
            assertTrue(text.contains("firstNameSyntheticlastNamePatient") && text.contains("firstNameSyntheticlastNameDoctor"),
                    "PDF must retain both frozen identities");
            assertTrue(text.contains("COMPLETED") && text.contains("configurationVersion1"), "PDF must retain execution and configuration");
            assertTrue(text.contains(NOTICE.replaceAll("\\s", "")), "Confirmed absence requires its explicit notice");
            assertTrue(text.contains("notadiagnosis"), "PDF requires the non-diagnostic notice");
            assertTrue(text.contains("ResultsVisualSearchresultsnotincluded"),
                    "The older omitted copy requires its explicit not-included notice");
            assertFalse(text.contains("Reportedattempts") || text.contains("Mediansearchduration(ms)"),
                    "An omitted Visual Search section must not fabricate zero results");
            assertFalse(text.contains(accessToken), "PDF must not contain a bearer token");
            assertFalse(text.contains(DOCTOR.value()), "PDF must not contain a Cognito subject");
            for (var page : pdf.getPages()) for (var name : page.getResources().getFontNames())
                assertTrue(page.getResources().getFont(name).isEmbedded(), "PDF must use its packaged font");
        }
        counts(0, 0, 0);
        getCopy(PATIENT, OWNED_SESSION, copy);
        counts(0, 0, 0);
    }

    @Test void pdfHeatmapUsesTheSavedGridWithoutEnrichmentOrPersistedChanges() throws Exception {
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, true, true);
        var response = getPdf(accessToken, PATIENT, OWNED_SESSION, heatmapCopy.id().toString());
        assertUnchanged(); counts(0, 0, 0);
        assertEquals(200, response.statusCode()); assertEquals("application/pdf", response.contentType());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals("attachment; filename=\"session-report-" + heatmapCopy.id() + ".pdf\"", response.header("Content-Disposition"));
        byte[] bytes = response.asByteArray();
        assertTrue(bytes.length > 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-');
        try (var pdf = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains(heatmapCopy.id().toString()) && text.contains(heatmapCopy.createdAt().toString()));
            assertTrue(text.contains("Scale per chart: 0 to 4 samples per cell."));
            assertTrue(text.contains("Observed gaze samples: 10."));
            assertTrue(text.contains("Sequence gaps: 1.") && text.contains("session boundary unknown"));
            assertFalse(text.contains(NOTICE)); assertFalse(text.contains(accessToken)); assertFalse(text.contains(DOCTOR.value()));
            record Cell(int page, float x, float y) {}
            var cells = new ArrayList<Cell>();
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                var tokens = new PDFStreamParser(pdf.getPage(page)).parse();
                for (int i = 4; i < tokens.size(); i++) if (tokens.get(i) instanceof Operator op && op.getName().equals("re")
                        && tokens.get(i - 2) instanceof COSNumber width && tokens.get(i - 1) instanceof COSNumber height
                        && Math.abs(width.floatValue() - 28) < .01 && Math.abs(height.floatValue() - 28) < .01)
                    cells.add(new Cell(page, ((COSNumber) tokens.get(i - 4)).floatValue(), ((COSNumber) tokens.get(i - 3)).floatValue()));
            }
            assertEquals(100, cells.size(), "One saved stream must produce exactly one 10-by-10 grid");
            var first = cells.getFirst(); var image = new PDFRenderer(pdf).renderImageWithDPI(first.page(), 72, ImageType.RGB);
            for (int i = 0; i < 100; i++) {
                assertEquals(first.page(), cells.get(i).page());
                assertEquals(first.x() + (i % 10) * 28, cells.get(i).x(), .02);
                assertEquals(first.y() - (i / 10) * 28, cells.get(i).y(), .02);
            }
            int[] positions = {0, 9, 90, 99, 1}; int[] colors = {0x2673d9, 0x93b9ec, 0xc9dcf5, 0x5c96e2, 0xffffff};
            for (int i = 0; i < positions.length; i++) {
                var cell = cells.get(positions[i]);
                int actual = image.getRGB(Math.round(cell.x() + 14), Math.round(pdf.getPage(cell.page()).getMediaBox().getHeight() - cell.y() - 14));
                for (int shift : new int[]{16, 8, 0}) assertEquals((colors[i] >> shift) & 255, (actual >> shift) & 255, 2);
            }
            var pageText = new PDFTextStripper(); pageText.setStartPage(first.page() + 1); pageText.setEndPage(first.page() + 1);
            String chartPage = pageText.getText(pdf);
            assertTrue(chartPage.contains("Observed gaze-sample distribution") && chartPage.contains("Scale per chart: 0 to 4"));
            assertTrue(chartPage.contains("session boundary unknown") && chartPage.contains("Sequence gaps: 1."));
            assertSavedGazeGraphic(pdf);
        }
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) java.nio.file.Files.write(java.nio.file.Path.of(output + ".http-heatmap.pdf"), bytes);
        var stored = getSnapshot(accessToken, PATIENT, OWNED_SESSION, heatmapCopy.id());
        assertEquals(200, stored.statusCode());
        assertEquals(mapper.readTree(heatmapCopy.reportJson()), mapper.readTree(stored.asString()).path("report"));
        assertUnchanged(); counts(0, 0, 0);
    }

    private static void assertSavedGazeGraphic(org.apache.pdfbox.pdmodel.PDDocument pdf) throws Exception {
        record Mark(int page, float x, float y) {}
        record Segment(int page, float x1, float y1, float x2, float y2) {}
        var marks = new ArrayList<Mark>(); var segments = new ArrayList<Segment>();
        for (int page = 0; page < pdf.getNumberOfPages(); page++) {
            var tokens = new PDFStreamParser(pdf.getPage(page)).parse(); boolean blue = false;
            for (int i = 3; i < tokens.size(); i++) if (tokens.get(i) instanceof Operator op) {
                if (op.getName().equals("G")) blue = false;
                if (op.getName().equals("RG")) blue = Math.abs(((COSNumber) tokens.get(i - 3)).floatValue() - .15f) < .001;
                if (op.getName().equals("re") && i >= 4
                        && ((COSNumber) tokens.get(i - 2)).floatValue() == 4 && ((COSNumber) tokens.get(i - 1)).floatValue() == 4)
                    marks.add(new Mark(page, ((COSNumber) tokens.get(i - 4)).floatValue(), ((COSNumber) tokens.get(i - 3)).floatValue()));
                if (blue && op.getName().equals("l") && i >= 5 && tokens.get(i - 3) instanceof Operator prior && prior.getName().equals("m"))
                    segments.add(new Segment(page, ((COSNumber) tokens.get(i - 5)).floatValue(), ((COSNumber) tokens.get(i - 4)).floatValue(),
                            ((COSNumber) tokens.get(i - 2)).floatValue(), ((COSNumber) tokens.get(i - 1)).floatValue()));
            }
        }
        assertEquals(4, marks.size(), "Exactly the four frozen gaze points must be drawn");
        assertEquals(2, segments.size(), "The saved break must prevent a connecting segment");
        var first = marks.getFirst(); float[] dx = {0, 84, 196, 224}, dy = {0, -84, -196, 28};
        for (int i = 0; i < 4; i++) {
            assertEquals(first.page(), marks.get(i).page());
            assertEquals(first.x() + dx[i], marks.get(i).x(), .02);
            assertEquals(first.y() + dy[i], marks.get(i).y(), .02);
        }
        for (int i = 0; i < 2; i++) {
            var from = marks.get(i * 2); var to = marks.get(i * 2 + 1); var line = segments.get(i);
            assertEquals(first.page(), line.page());
            assertEquals(from.x() + 2, line.x1(), .02); assertEquals(from.y() + 2, line.y1(), .02);
            assertEquals(to.x() + 2, line.x2(), .02); assertEquals(to.y() + 2, line.y2(), .02);
        }
        var text = new PDFTextStripper(); text.setStartPage(first.page() + 1); text.setEndPage(first.page() + 1);
        String chart = text.getText(pdf);
        assertTrue(chart.contains("Estimated gaze-point trajectory") && chart.contains("Saved points shown: 4."));
        assertTrue(chart.contains("Partial saved selection") && chart.contains("Sequence gaps: 1."));
        assertTrue(chart.contains("session boundary unknown") && chart.contains("Not physiological fixations"));
    }

    @Test void pdfRejectsInvalidTokensAndRolesBeforeEnrichment() throws Exception {
        String id = new UUID(26, 90).toString();
        String tampered = accessToken.substring(0, accessToken.lastIndexOf('.') + 1) + "bad-signature";
        for (String invalid : Arrays.asList(null, "not-a-jwt", tampered,
                token(List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            frameworkUnauthenticated(getPdf(invalid, PATIENT, OWNED_SESSION, id));
            assertUnchanged();
        }
        for (String role : List.of("PATIENT", "ADMIN")) {
            frameworkForbidden(getPdf(token(List.of(role), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION, id));
            assertUnchanged();
        }
        for (String role : List.of("PATIENT", "ADMIN"))
            pdfError(getPdf(token(List.of("DOCTOR", role), Instant.now().plusSeconds(300)), PATIENT, OWNED_SESSION, id), 403, "FORBIDDEN");
    }

    @Test void pdfRequiresCurrentScopeAndExistingCopy() throws Exception {
        String id = new UUID(26, 91).toString();
        for (UUID session : List.of(FOREIGN_SESSION, new UUID(22, 99)))
            pdfError(getPdf(accessToken, PATIENT, session, id), 403, "FORBIDDEN");
        pdfError(getPdf(accessToken, PATIENT, OWNED_SESSION, id), 404, "NOT_FOUND");
        for (String invalid : List.of("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A"))
            pdfError(getPdf(accessToken, PATIENT, OWNED_SESSION, invalid), 400, "INVALID_REQUEST");
    }

    @Test void visualSearchPreviewPreservesBothOriginsAndFractionalMetrics() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            visualScenario(origin, OBSERVED);
            var report = visualPreview(origin);
            assertVisualSearchSummary(report, origin, expectedVisualSummary(origin, OBSERVED));
            counts(1, 1, 1); visualReads(1);
        }
    }

    @Test void visualSearchOmissionAndConfirmedZeroRemainDistinctFromBaselineAbsence() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        var omitted = visualPreview(null);
        assertFalse(omitted.has("visualSearch"), "Omitted selection must not imply zero trials");
        counts(1, 1, 1); visualReads(0);
        visualScenario("SYNTHETIC", ZERO);
        var zero = visualPreview("SYNTHETIC");
        assertVisualSearchSummary(zero, "SYNTHETIC", expectedVisualSummary("SYNTHETIC", ZERO));
        assertTrue(zero.path("visualSearch").path("medianSearchDurationMs").isNull());
        counts(1, 1, 1); visualReads(1);
        visualScenario("SYNTHETIC", ZERO);
        var copy = createCopy(PATIENT, VISUAL_SEARCH_SESSION, "http-visual-zero-pdf", "SYNTHETIC", expectedVisualSummary("SYNTHETIC", ZERO));
        counts(1, 1, 1); visualReads(1);
        peers.scenario(PATIENT.value(), VISUAL_SEARCH_SESSION, accessToken, true, true);
        visualSearchPdf(copy);
        getCopy(PATIENT, VISUAL_SEARCH_SESSION, copy);
        counts(0, 0, 0); visualReads(0);
    }

    @Test void visualSearchSavedCopyAndMatchingReplayStayFrozenAfterPeerChange() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            String key = "http-visual-frozen-" + origin;
            visualScenario(origin, OBSERVED);
            var copy = createCopy(PATIENT, VISUAL_SEARCH_SESSION, key, origin, expectedVisualSummary(origin, OBSERVED));
            counts(1, 1, 1); visualReads(1);
            visualScenario(origin, CHANGED);
            visualSearchPdf(copy);
            getCopy(PATIENT, VISUAL_SEARCH_SESSION, copy);
            counts(0, 0, 0); visualReads(0);
            var replay = postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, key, origin);
            assertUnchanged();
            assertEquals(200, replay.statusCode()); assertEquals("no-store", replay.header("Cache-Control"));
            assertTrue(replay.contentType().startsWith("application/json")); assertNull(replay.header("Location"));
            assertTrue(copy.equals(mapper.readTree(replay.asString())), "Replay must preserve the exact selected saved copy");
            counts(0, 0, 0); visualReads(0);
        }
    }

    @Test void visualSearchSelectionConflictsPreserveCopiesAndNewKeysRemainIndependent() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        var synthetic = createCopy(PATIENT, VISUAL_SEARCH_SESSION, "http-visual-synthetic", "SYNTHETIC", expectedVisualSummary("SYNTHETIC", OBSERVED));
        counts(1, 1, 1); visualReads(1);
        visualScenario("PRODUCER_REPORTED", OBSERVED);
        for (String origin : Arrays.asList("PRODUCER_REPORTED", null)) {
            snapshotError(postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-synthetic", origin), 409, "SELECTION_CONFLICT");
            visualReads(0);
        }
        var producer = createCopy(PATIENT, VISUAL_SEARCH_SESSION, "http-visual-producer", "PRODUCER_REPORTED", expectedVisualSummary("PRODUCER_REPORTED", OBSERVED));
        counts(1, 1, 1); visualReads(1);
        assertFalse(synthetic.path("reportSnapshotId").equals(producer.path("reportSnapshotId")), "A new key must create an independent copy");
        visualScenario("SYNTHETIC", OBSERVED);
        snapshotError(postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-producer", "SYNTHETIC"), 409, "SELECTION_CONFLICT");
        visualReads(0);
        var omitted = createCopy(PATIENT, VISUAL_SEARCH_SESSION, "http-visual-omitted");
        counts(1, 1, 1); visualReads(0);
        visualScenario("SYNTHETIC", OBSERVED);
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            snapshotError(postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-omitted", origin), 409, "SELECTION_CONFLICT");
            visualReads(0);
        }
        getCopy(PATIENT, VISUAL_SEARCH_SESSION, synthetic);
        getCopy(PATIENT, VISUAL_SEARCH_SESSION, producer);
        getCopy(PATIENT, VISUAL_SEARCH_SESSION, omitted);
        counts(0, 0, 0); visualReads(0);
    }

    @Test void visualSearchInvalidOriginAndUnsupportedFixationAre400WithoutEnrichment() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        for (String origin : List.of("", "synthetic", "UNKNOWN", " SYNTHETIC")) {
            counts(0, 0, 0); visualReads(0);
            resourceError(request(accessToken, PATIENT, VISUAL_SEARCH_SESSION, origin), 400, "SESSION_REPORT_INVALID_REQUEST");
            assertUnchanged(); counts(0, 0, 0); visualReads(0);
            counts(0, 0, 0); visualReads(0);
            snapshotError(postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-invalid", origin), 400, "INVALID_REQUEST");
            visualReads(0);
        }
        peers.scenario(PATIENT.value(), OWNED_SESSION, accessToken, false, false);
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            counts(0, 0, 0); visualReads(0);
            resourceError(request(accessToken, PATIENT, OWNED_SESSION, origin), 400, "SESSION_REPORT_INVALID_REQUEST");
            assertUnchanged(); counts(0, 0, 0); visualReads(0);
            counts(0, 0, 0); visualReads(0);
            snapshotError(postSnapshot(accessToken, PATIENT, OWNED_SESSION, "http-visual-unsupported", origin), 400, "INVALID_REQUEST");
            visualReads(0);
        }
    }

    @Test void visualSearchSelectedRoutesRetainNativeAuthenticationAndPureDoctorGates() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        frameworkUnauthenticated(request(null, PATIENT, VISUAL_SEARCH_SESSION, "SYNTHETIC")); assertUnchanged(); visualReads(0);
        frameworkUnauthenticated(postSnapshot(null, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-auth", "SYNTHETIC")); assertUnchanged(); visualReads(0);
        // Existing tests retain the full malformed/tampered/expired token matrix; these pin the selected routes.
        for (String role : List.of("PATIENT", "ADMIN")) {
            String nonDoctor = token(List.of(role), Instant.now().plusSeconds(300));
            frameworkForbidden(request(nonDoctor, PATIENT, VISUAL_SEARCH_SESSION, "SYNTHETIC")); assertUnchanged(); visualReads(0);
            frameworkForbidden(postSnapshot(nonDoctor, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-role", "SYNTHETIC")); assertUnchanged(); visualReads(0);
            String mixed = token(List.of("DOCTOR", role), Instant.now().plusSeconds(300));
            resourceError(request(mixed, PATIENT, VISUAL_SEARCH_SESSION, "SYNTHETIC"), 403, "SESSION_REPORT_FORBIDDEN");
            assertUnchanged(); counts(0, 0, 0); visualReads(0);
            snapshotError(postSnapshot(mixed, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-mixed", "SYNTHETIC"), 403, "FORBIDDEN");
            visualReads(0);
        }
    }

    @Test void visualSearchSelectedRoutesDenyForeignAuthorAndRevokedAssignmentBeforePeers() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        resourceError(request(accessToken, PATIENT, FOREIGN_VISUAL_SEARCH_SESSION, "SYNTHETIC"), 403, "SESSION_REPORT_FORBIDDEN");
        assertUnchanged(); counts(0, 0, 0); visualReads(0);
        snapshotError(postSnapshot(accessToken, PATIENT, FOREIGN_VISUAL_SEARCH_SESSION, "http-visual-foreign", "SYNTHETIC"), 403, "FORBIDDEN");
        visualReads(0);
        revokeAssignment(VISUAL_SEARCH_REVOKED);
        peers.scenario(VISUAL_SEARCH_REVOKED.value(), VISUAL_SEARCH_REVOKED_SESSION, accessToken, false, false);
        peers.visualSearchScenario(new UUID(25, 9), "SYNTHETIC", OBSERVED);
        resourceError(request(accessToken, VISUAL_SEARCH_REVOKED, VISUAL_SEARCH_REVOKED_SESSION, "SYNTHETIC"), 403, "SESSION_REPORT_FORBIDDEN");
        assertUnchanged(); counts(0, 0, 0); visualReads(0);
        snapshotError(postSnapshot(accessToken, VISUAL_SEARCH_REVOKED, VISUAL_SEARCH_REVOKED_SESSION, "http-visual-revoked", "SYNTHETIC"), 403, "FORBIDDEN");
        visualReads(0);
    }

    @Test void visualSearchUpstreamFailuresAndBindingMismatchesNeverPersistOrReplaceCopies() throws Exception {
        visualScenario("SYNTHETIC", OBSERVED);
        var copy = createCopy(PATIENT, VISUAL_SEARCH_SESSION, "http-visual-before-failures", "SYNTHETIC", expectedVisualSummary("SYNTHETIC", OBSERVED));
        counts(1, 1, 1); visualReads(1);
        for (var failure : List.of(UNAVAILABLE, WRONG_SESSION, WRONG_CONFIGURATION, WRONG_VERSION, WRONG_ORIGIN)) {
            visualScenario("SYNTHETIC", failure);
            resourceError(request(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "SYNTHETIC"), 503, "SESSION_REPORT_UNAVAILABLE");
            assertUnchanged(); counts(1, 1, 1); visualReads(1);
            visualScenario("SYNTHETIC", failure);
            resourceError(postSnapshot(accessToken, PATIENT, VISUAL_SEARCH_SESSION, "http-visual-failure-" + failure.name(), "SYNTHETIC"),
                    503, "SESSION_REPORT_SNAPSHOT_UNAVAILABLE");
            assertUnchanged(); counts(1, 1, 1); visualReads(1);
        }
        visualScenario("SYNTHETIC", UNAVAILABLE);
        getCopy(PATIENT, VISUAL_SEARCH_SESSION, copy);
        counts(0, 0, 0); visualReads(0);
    }

    private void visualScenario(String origin, VisualSearchScenario scenario) {
        peers.scenario(PATIENT.value(), VISUAL_SEARCH_SESSION, accessToken, false, false);
        peers.visualSearchScenario(VISUAL_SEARCH_CONFIGURATION, origin, scenario);
    }

    private JsonNode visualPreview(String origin) throws Exception {
        var response = request(accessToken, PATIENT, VISUAL_SEARCH_SESSION, origin);
        assertUnchanged(); assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        var report = mapper.readTree(response.asString());
        var fields = new HashSet<String>(); report.fieldNames().forEachRemaining(fields::add);
        var expected = new HashSet<>(Set.of("schemaVersion", "clinicalPatientId", "issuedAt", "mode", "analyticsNotice", "doctor",
                "patient", "execution", "configuration", "sessionLimits", "technicalProgress", "analytics"));
        if (origin != null) expected.add("visualSearch");
        assertEquals(expected, fields);
        assertEquals("TARGET_SEARCH_WITH_DISTRACTORS", report.path("mode").asText());
        assertEquals(VISUAL_SEARCH_SESSION.toString(), report.path("execution").path("therapySessionId").asText());
        assertEquals("COMPLETED", report.path("execution").path("status").asText());
        assertEquals(VISUAL_SEARCH_CONFIGURATION.toString(), report.path("configuration").path("configurationId").asText());
        assertEquals(1, report.path("configuration").path("configurationVersion").asLong());
        assertEquals("CONFIRMED_NO_EVIDENCE", report.path("analytics").path("availability").asText());
        assertTrue(report.path("analytics").path("streams").isArray() && report.path("analytics").path("streams").isEmpty());
        assertEquals(NOTICE, report.path("analyticsNotice").asText());
        assertFalse(response.asString().contains(accessToken), "Report must not contain a bearer token");
        assertFalse(response.asString().contains(DOCTOR.value()), "Report must not contain a Cognito subject");
        return report;
    }

    private JsonNode expectedVisualSummary(String origin, VisualSearchScenario scenario) {
        boolean zero = scenario == ZERO, changed = scenario == CHANGED;
        var summary = mapper.createObjectNode().put("calculationVersion", "visual-search-trial-v1")
                .put("therapySessionId", VISUAL_SEARCH_SESSION.toString()).put("configurationId", VISUAL_SEARCH_CONFIGURATION.toString())
                .put("configurationVersion", 1).put("dataOrigin", origin).put("reportedTrialCount", zero ? 0 : changed ? 4 : 6)
                .put("hitCount", zero ? 0 : changed ? 2 : 3).put("omissionCount", zero ? 0 : changed ? 1 : 2)
                .put("interruptedCount", zero ? 0 : 1).put("eligibleSearchCount", zero ? 0 : changed ? 2 : 3);
        if (zero) summary.putNull("medianSearchDurationMs"); else summary.put("medianSearchDurationMs", changed ? 2500.5 : 1250.5);
        return summary;
    }

    private void assertVisualSearchSummary(JsonNode report, String origin, JsonNode expected) {
        var summary = report.path("visualSearch");
        var fields = new HashSet<String>(); summary.fieldNames().forEachRemaining(fields::add);
        assertTrue(summary.isObject());
        assertEquals(Set.of("calculationVersion", "therapySessionId", "configurationId", "configurationVersion", "dataOrigin",
                "reportedTrialCount", "hitCount", "omissionCount", "interruptedCount", "eligibleSearchCount", "medianSearchDurationMs"), fields);
        for (String field : List.of("configurationVersion", "reportedTrialCount", "hitCount", "omissionCount", "interruptedCount", "eligibleSearchCount"))
            assertTrue(summary.path(field).isIntegralNumber(), "Visual Search counts and version must remain integral");
        assertTrue(summary.path("medianSearchDurationMs").isNull() || summary.path("medianSearchDurationMs").isNumber());
        assertTrue(expected != null && expected.equals(summary), "Visual Search summary must preserve the exact selected observations");
        assertEquals(origin, summary.path("dataOrigin").asText());
        assertTrue(report.path("execution").path("therapySessionId").equals(summary.path("therapySessionId")), "Visual Search session binding changed");
        assertTrue(report.path("configuration").path("configurationId").equals(summary.path("configurationId")), "Visual Search configuration binding changed");
        assertEquals(report.path("configuration").path("configurationVersion").asLong(), summary.path("configurationVersion").asLong());
        assertEquals("TARGET_SEARCH_WITH_DISTRACTORS", report.path("mode").asText());
    }

    private void visualReads(int expected) { assertEquals(expected, peers.visualSearchCount(), "Unexpected Visual Search peer read count"); }

    private void visualSearchPdf(JsonNode copy) throws Exception {
        String id = copy.path("reportSnapshotId").asText();
        var selected = copy.path("report").path("visualSearch");
        assertTrue(selected.isObject(), "The expected saved copy must contain its selected summary");
        var response = getPdf(accessToken, PATIENT, VISUAL_SEARCH_SESSION, id);
        assertUnchanged();
        assertEquals(200, response.statusCode()); assertEquals("application/pdf", response.contentType());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(("attachment; filename=\"session-report-" + id + ".pdf\"").equals(response.header("Content-Disposition")),
                "PDF filename must contain only the saved copy reference");
        byte[] bytes = response.asByteArray();
        assertTrue(bytes.length > 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-',
                "Successful download must contain a PDF document");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() > 0, "PDF requires readable pages");
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s", "");
            assertTrue(text.contains(id) && text.contains(copy.path("createdAt").asText()), "PDF must retain the saved copy reference and creation time");
            assertTrue(text.contains("VisualSearch-reportedfinalresults"), "PDF must display the selected saved-results table");
            assertTrue(text.contains("notadiagnosis"), "PDF requires the non-diagnostic notice");
            assertTrue(text.contains(NOTICE.replaceAll("\\s", "")), "Baseline evidence absence remains independent of Visual Search results");
            assertTrue(text.contains("Dataorigin" + selected.path("dataOrigin").asText() + "Calculationversion"), "PDF origin must match the frozen summary row");
            assertTrue(text.contains("Calculationversion" + selected.path("calculationVersion").asText() + "Reportedattempts"), "PDF calculation provenance must match the frozen summary row");
            String[][] rows = {{"Reportedattempts", "reportedTrialCount", "Hits"}, {"Hits", "hitCount", "Omissions"},
                    {"Omissions", "omissionCount", "Interruptions"}, {"Interruptions", "interruptedCount", "Eligiblesearches"},
                    {"Eligiblesearches", "eligibleSearchCount", "Mediansearchduration(ms)"}};
            for (var row : rows)
                assertTrue(text.contains(row[0] + selected.path(row[1]).asText() + row[2]), "PDF metric row must match the exact frozen value");
            var savedMedian = selected.path("medianSearchDurationMs");
            String median = savedMedian.isNull() ? "Notavailable" : savedMedian.decimalValue().toPlainString();
            assertTrue(text.contains("Mediansearchduration(ms)" + median + "Meaning"), "PDF median must match the frozen numeric-or-unavailable row");
            assertFalse(text.contains("Mediansearchduration(ms)2500.5Meaning"), "PDF must not refresh the changed peer median");
            assertFalse(text.contains("VisualSearchresultsnotincluded"), "A selected zero summary must not become omitted results");
            String warning = selected.path("dataOrigin").asText().equals("SYNTHETIC")
                    ? "SYNTHETIC:testdata,notpatientevidence."
                    : "PRODUCER_REPORTED:producer-reportedoutcomes;notalgorithmicallyverifiedclinicalsuccess.";
            assertTrue(text.contains(warning), "PDF requires the explicit saved-origin limitation");
        }
        counts(0, 0, 0); visualReads(0);
    }

    private Response getPdf(String token, ClinicalPatientId patient, UUID session, String id) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.get(snapshotPath(patient, session) + "/" + id + "/pdf");
    }

    private void pdfError(Response response, int status, String suffix) throws Exception {
        snapshotError(response, status, suffix);
        assertTrue(response.contentType().startsWith("application/json"), "Application PDF errors must be JSON");
        assertNull(response.header("Content-Disposition"), "Rejected requests must not advertise a PDF attachment");
    }

    private Response postSnapshot(String token, ClinicalPatientId patient, UUID session, String key) {
        return postSnapshot(token, patient, session, key, null);
    }

    private Response postSnapshot(String token, ClinicalPatientId patient, UUID session, String key, String origin) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.queryParam("visualSearchDataOrigin", origin);
        return request.header("Idempotency-Key", key).post(snapshotPath(patient, session));
    }

    private Response getSnapshot(String token, ClinicalPatientId patient, UUID session, UUID id) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.get(snapshotPath(patient, session) + "/" + id);
    }

    private Response listSnapshots(String token, ClinicalPatientId patient, UUID session, String query) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.get(snapshotPath(patient, session) + (query.isEmpty() ? "" : "?" + query));
    }

    private JsonNode listPage(ClinicalPatientId patient, UUID session, String query, int page, int size, long total) throws Exception {
        var response = listSnapshots(accessToken, patient, session, query);
        assertUnchanged();
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        var body = mapper.readTree(response.asString());
        var fields = new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("clinicalPatientId", "therapySessionId", "snapshots", "page", "size", "total"), fields);
        assertEquals(patient.value().toString(), body.path("clinicalPatientId").asText());
        assertEquals(session.toString(), body.path("therapySessionId").asText());
        assertTrue(body.path("snapshots").isArray());
        assertTrue(body.path("page").isIntegralNumber() && body.path("size").isIntegralNumber() && body.path("total").isIntegralNumber());
        assertEquals(page, body.path("page").asInt()); assertEquals(size, body.path("size").asInt());
        assertEquals(total, body.path("total").asLong());
        counts(0, 0, 0);
        return body;
    }

    private void assertSummary(JsonNode copy, JsonNode summary) {
        var fields = new HashSet<String>(); summary.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("reportSnapshotId", "createdAt"), fields);
        assertEquals(copy.path("reportSnapshotId"), summary.path("reportSnapshotId"));
        assertEquals(copy.path("createdAt"), summary.path("createdAt"));
    }

    private static String snapshotPath(ClinicalPatientId patient, UUID session) {
        return "/api/v1/clinical-patients/" + patient.value() + "/therapy-sessions/" + session + "/report-snapshots";
    }

    private void assertUnchanged() {
        assertTrue(before.equals(snapshot()), "HTTP snapshot operation changed persisted Clinical data");
    }

    private void snapshotError(Response response, int status, String suffix) throws Exception {
        assertUnchanged();
        resourceError(response, status, "SESSION_REPORT_SNAPSHOT_" + suffix);
        counts(0, 0, 0);
    }

    private void getCopy(ClinicalPatientId patient, UUID session, JsonNode copy) throws Exception {
        var response = getSnapshot(accessToken, patient, session, UUID.fromString(copy.path("reportSnapshotId").asText()));
        assertUnchanged();
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        assertTrue(copy.equals(mapper.readTree(response.asString())), "GET changed the frozen copy");
    }

    private JsonNode createCopy(ClinicalPatientId patient, UUID session, String key) throws Exception {
        return createCopy(patient, session, key, null, null);
    }

    private JsonNode createCopy(ClinicalPatientId patient, UUID session, String key, String origin, JsonNode expectedSummary) throws Exception {
        assertUnchanged();
        var response = postSnapshot(accessToken, patient, session, key, origin);
        assertEquals(201, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        var copy = mapper.readTree(response.asString());
        assertTrue(copy.isObject() && copy.size() == 3 && copy.has("reportSnapshotId")
                && copy.has("createdAt") && copy.has("report"), "Unexpected snapshot wrapper");
        UUID id = UUID.fromString(copy.path("reportSnapshotId").asText());
        Instant created = Instant.parse(copy.path("createdAt").asText());
        String locationHeader = response.header("Location");
        assertNotNull(locationHeader, "New snapshot must advertise its location");
        URI location = URI.create(locationHeader);
        assertTrue((snapshotPath(patient, session) + "/" + id).equals(location.getRawPath())
                && location.getRawQuery() == null && location.getRawFragment() == null
                && location.getRawUserInfo() == null, "Snapshot Location must identify the canonical local path");
        if (location.isAbsolute()) {
            assertTrue("http".equals(location.getScheme()) && "127.0.0.1".equals(location.getHost())
                    && location.getPort() == http.getPort(), "Absolute Location must stay on the owned HTTP origin");
        } else assertNull(location.getRawAuthority(), "Relative Location must not name another authority");
        var report = copy.path("report");
        assertTrue(report.isObject() && report.path("schemaVersion").asInt() == 1
                && patient.value().toString().equals(report.path("clinicalPatientId").asText())
                && session.toString().equals(report.path("execution").path("therapySessionId").asText())
                && "COMPLETED".equals(report.path("execution").path("status").asText())
                && report.path("configuration").path("configurationVersion").asLong() == 1,
                "Stored report must match the known completed synthetic session");
        assertTrue("Synthetic".equals(report.path("patient").path("firstName").asText())
                && "Patient".equals(report.path("patient").path("lastName").asText())
                && "Synthetic".equals(report.path("doctor").path("firstName").asText())
                && "Doctor".equals(report.path("doctor").path("lastName").asText())
                && "CONFIRMED_NO_EVIDENCE".equals(report.path("analytics").path("availability").asText())
                && report.path("analytics").path("streams").isArray()
                && report.path("analytics").path("streams").isEmpty()
                && NOTICE.equals(report.path("analyticsNotice").asText()), "Unexpected synthetic report content");
        assertFalse(response.asString().contains(accessToken), "Snapshot must not contain a bearer token");
        assertFalse(response.asString().contains(DOCTOR.value()), "Snapshot must not contain a Cognito subject");
        if (origin == null) assertFalse(report.has("visualSearch"), "Omitted selection must not create a Visual Search section");
        else assertVisualSearchSummary(report, origin, expectedSummary);
        var after = snapshot();
        assertTrue(before.keySet().equals(after.keySet()), "Snapshot creation changed the application table set");
        for (String table : before.keySet()) {
            if (!SNAPSHOT_TABLE.equals(table))
                assertTrue(before.get(table).equals(after.get(table)), "Snapshot creation changed another application table");
        }
        assertTrue(before.containsKey(SNAPSHOT_TABLE), "V10 snapshot table required");
        var previous = before.get(SNAPSHOT_TABLE);
        var current = after.get(SNAPSHOT_TABLE);
        assertTrue(current.size() == previous.size() + 1 && current.containsAll(previous),
                "Creation must preserve every old copy and add exactly one row");
        var added = new ArrayList<>(current);
        added.removeAll(previous);
        assertEquals(1, added.size(), "Exactly one new snapshot row required");
        var row = mapper.readTree(added.getFirst().toString());
        assertTrue(row.isObject() && row.size() == 7 && id.toString().equals(row.path("id").asText())
                && patient.value().toString().equals(row.path("clinical_patient_id").asText())
                && session.toString().equals(row.path("therapy_session_id").asText())
                && DOCTOR.value().equals(row.path("doctor_cognito_sub").asText())
                && key.equals(row.path("idempotency_key").asText())
                && created.equals(Instant.parse(row.path("created_at").asText()))
                && row.path("report_json").isTextual()
                && report.equals(mapper.readTree(row.path("report_json").asText())),
                "New persisted row must match the exact response and authorized request context");
        // Only this fully verified single-row insertion can advance the full-schema baseline.
        before = after;
        return copy;
    }

    private void revokeAssignment(ClinicalPatientId patient) throws Exception {
        assertUnchanged();
        QuarkusTransaction.requiringNew().run(() -> {
            var assignment = assignments.findActiveByClinicalPatientIdAndDoctorSubject(patient, DOCTOR).orElseThrow();
            assignment.end(T.plusSeconds(3600));
            assignments.save(assignment);
            entities.flush();
        });
        var after = snapshot();
        String table = "doctor_patient_assignments";
        assertTrue(before.keySet().equals(after.keySet()), "Revocation setup changed the application table set");
        for (String other : before.keySet()) {
            if (!table.equals(other)) assertTrue(before.get(other).equals(after.get(other)),
                    "Revocation setup changed another application table");
        }
        var removed = new ArrayList<>(before.get(table));
        removed.removeAll(after.get(table));
        var added = new ArrayList<>(after.get(table));
        added.removeAll(before.get(table));
        assertTrue(removed.size() == 1 && added.size() == 1, "Revocation must update exactly one assignment");
        ObjectNode old = (ObjectNode) mapper.readTree(removed.getFirst().toString());
        ObjectNode changed = (ObjectNode) mapper.readTree(added.getFirst().toString());
        assertTrue(patient.value().toString().equals(old.path("clinical_patient_id").asText())
                && DOCTOR.value().equals(old.path("doctor_cognito_sub").asText())
                && "ACTIVE".equals(old.path("status").asText()) && old.path("ended_at").isNull()
                && "ENDED".equals(changed.path("status").asText())
                && T.plusSeconds(3600).equals(Instant.parse(changed.path("ended_at").asText()))
                && changed.path("version").asLong() == old.path("version").asLong() + 1,
                "Unexpected assignment revocation delta");
        old.remove(List.of("status", "ended_at", "version"));
        changed.remove(List.of("status", "ended_at", "version"));
        assertTrue(old.equals(changed), "Revocation changed immutable assignment fields");
        // Verify the deliberate fixture change before excluding it from subsequent request deltas.
        before = after;
    }

    private Response request(String token, ClinicalPatientId patient, UUID session) {
        return request(token, patient, session, null);
    }

    private Response request(String token, ClinicalPatientId patient, UUID session, String origin) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.queryParam("visualSearchDataOrigin", origin);
        return request.get("/api/v1/clinical-patients/" + patient.value()
                + "/therapy-sessions/" + session + "/report-preview");
    }

    private void frameworkUnauthenticated(Response response) {
        assertEquals(401, response.statusCode());
        String challenge = response.header("WWW-Authenticate");
        assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                "Framework authentication rejection must advertise Bearer");
        // Framework interception does not promise the resource's JSON body or no-store header.
        counts(0, 0, 0);
    }

    private void frameworkForbidden(Response response) {
        assertEquals(403, response.statusCode());
        counts(0, 0, 0);
    }

    private void resourceError(Response response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        assertNull(response.header("Location"), "Rejected requests must not advertise a new copy");
        var json = mapper.readTree(response.asString());
        assertEquals(1, json.size(), "Resource error must stay generic");
        assertEquals(code, json.path("code").asText());
        assertFalse(response.asString().contains(NOTICE), "Failure must never become analytical absence");
    }

    private void counts(int names, int doctor, int analytics) {
        assertEquals(new SessionReportPeersTestResource.Counts(names, doctor, analytics, 0, 0), peers.counts(),
                "Peer counts or exact original-bearer forwarding did not match");
    }

    private static String token(List<String> groups, Instant expiresAt) {
        // Same real JWT signer and local public-key resource as TelemetryAuthorizationRealOidcTest.
        return Jwt.subject(DOCTOR.value()).issuer("https://server.example.com")
                .claim("client_id", "test-client").claim("token_use", "access")
                .claim("cognito:groups", groups).expiresAt(expiresAt.getEpochSecond()).sign();
    }

    private void seedPatient(ClinicalPatientId id, long number) {
        patients.save(ClinicalPatient.registerInvited(id,
                new CognitoSubject("synthetic-report-http-patient-" + number), T.minusSeconds(2000)));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(new UUID(23, number)),
                id, DOCTOR, T.minusSeconds(1500)));
    }

    private void seedSession(ClinicalPatientId patient, CognitoSubject doctor, UUID session, long number) {
        seedSession(patient, doctor, session, number, TherapeuticConfigurationTestData.fixationSnapshot().parameters());
    }

    private void seedSession(ClinicalPatientId patient, CognitoSubject doctor, UUID session, long number,
            TherapeuticExerciseParameters parameters) {
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(new UUID(24, number)), patient, doctor,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10,
                Map.of(parameters.exercise().code(), 10));
        plans.save(plan);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(25, number)),
                plan.id(), parameters.exercise(), 1, parameters.schemaVersion(), T.minusSeconds(1000), doctor,
                T.minusSeconds(1000), parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(session), plan.id(), patient, doctor,
                parameters.exercise().code(), TherapeuticConfigurationSnapshot.from(configuration),
                LocalDate.of(2026, 10, 21), new IdempotencyKey("synthetic-report-http-" + number),
                "a".repeat(64), 1, 15, 10, TherapySessionStatus.COMPLETED, 90, 10, true,
                T, null, T.plusSeconds(120), null));
    }

    private Map<String, List<?>> snapshot() {
        return QuarkusTransaction.requiringNew().call(this::snapshotInTransaction);
    }

    private Map<String, List<?>> snapshotInTransaction() {
        // Discover every application table, including any request/report table accidentally added later.
        List<?> tables = entities.createNativeQuery("SELECT tablename FROM pg_catalog.pg_tables "
                + "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' ORDER BY tablename").getResultList();
        var result = new LinkedHashMap<String, List<?>>();
        for (Object value : tables) {
            String table = value.toString();
            assertTrue(table.matches("[a-z][a-z0-9_]*"), "Unsupported synthetic database table name");
            List<?> rows = entities.createNativeQuery("SELECT CAST(to_jsonb(t) AS text) FROM \"" + table
                    + "\" t ORDER BY CAST(to_jsonb(t) AS text)").getResultList();
            result.put(table, List.copyOf(rows));
        }
        assertFalse(result.isEmpty(), "Migrated Clinical schema required");
        return result;
    }
}
