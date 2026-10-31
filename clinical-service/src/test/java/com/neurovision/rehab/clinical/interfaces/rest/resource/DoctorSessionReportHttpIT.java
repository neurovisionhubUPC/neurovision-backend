package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
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
    @Inject ObjectMapper mapper;
    @TestHTTPResource URI http;
    // Injected by the test resource, not a replacement CDI application bean.
    SessionReportPeersTestResource peers;
    private Map<String, List<?>> before;
    private String accessToken;

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
            entities.flush();
        });
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
            assertFalse(text.contains(accessToken), "PDF must not contain a bearer token");
            assertFalse(text.contains(DOCTOR.value()), "PDF must not contain a Cognito subject");
            for (var page : pdf.getPages()) for (var name : page.getResources().getFontNames())
                assertTrue(page.getResources().getFont(name).isEmbedded(), "PDF must use its packaged font");
        }
        counts(0, 0, 0);
        getCopy(PATIENT, OWNED_SESSION, copy);
        counts(0, 0, 0);
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
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.header("Idempotency-Key", key).post(snapshotPath(patient, session));
    }

    private Response getSnapshot(String token, ClinicalPatientId patient, UUID session, UUID id) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.get(snapshotPath(patient, session) + "/" + id);
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
        assertTrue(copy.equals(mapper.readTree(response.asString())), "GET changed the frozen copy");
    }

    private JsonNode createCopy(ClinicalPatientId patient, UUID session, String key) throws Exception {
        assertUnchanged();
        var response = postSnapshot(accessToken, patient, session, key);
        assertEquals(201, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
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
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
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
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(new UUID(24, number)), patient, doctor,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10,
                Map.of(TherapeuticExercise.FIXATION.code(), 10));
        plans.save(plan);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(new UUID(25, number)),
                plan.id(), TherapeuticExercise.FIXATION, 1, 1, T.minusSeconds(1000), doctor,
                T.minusSeconds(1000), TherapeuticConfigurationTestData.fixationSnapshot().parameters());
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(session), plan.id(), patient, doctor,
                TherapeuticExercise.FIXATION.code(), TherapeuticConfigurationSnapshot.from(configuration),
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
