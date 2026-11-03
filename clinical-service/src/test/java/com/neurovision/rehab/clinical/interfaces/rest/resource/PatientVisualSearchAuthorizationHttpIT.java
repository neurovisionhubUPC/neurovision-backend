package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeVisualSearchSessionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorVisualSearchSummaryQueryService;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

/** Opt-in real SQL/CDI/HTTP verification; preparation alone does not establish a passing result. */
@QuarkusTest
@TestProfile(ClinicalSessionReportHttpProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class PatientVisualSearchAuthorizationHttpIT {
    private static final CognitoSubject DOCTOR = new CognitoSubject("synthetic-visual-search-doctor");
    private static final CognitoSubject OTHER_DOCTOR = new CognitoSubject("synthetic-visual-search-other-doctor");
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    @Inject AgroalDataSource dataSource;
    @Inject Flyway flyway;
    @Inject EntityManager entities;
    @Inject ClinicalPatientRepository patients;
    @Inject DoctorPatientAssignmentRepository assignments;
    @Inject TherapyPlanRepository plans;
    @Inject TherapeuticExerciseConfigurationRepository configurations;
    @Inject TherapySessionRepository sessions;
    @Inject TherapeuticConfigurationJsonCodec codec;
    @Inject AuthorizeVisualSearchSessionQueryService authorization;
    @Inject AuthorizeDoctorVisualSearchSummaryQueryService doctorAuthorization;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URI http;
    SessionReportPeersTestResource peers;
    private Map<String, List<?>> before;

    @BeforeAll void attestMigrateAndSeedDedicatedDatabase() throws Exception {
        ClinicalSessionReportHttpProfile.requireOptIn();
        assertNotNull(authorization, "Production authorization service must resolve through CDI");
        assertNotNull(doctorAuthorization, "Production DOCTOR authorization service must resolve through CDI");
        assertTrue("http".equals(http.getScheme()) && "127.0.0.1".equals(http.getHost()),
                "Dedicated loopback HTTP binding required");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT current_database(), current_user, current_setting('server_version_num')")) {
            statement.setQueryTimeout(5);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next(), "Database attestation unavailable");
                assertTrue(ClinicalSessionReportHttpProfile.DATABASE.equals(row.getString(1))
                        && ClinicalSessionReportHttpProfile.USER.equals(row.getString(2)),
                        "Refusing a non-dedicated database or role");
                int version = Integer.parseInt(row.getString(3));
                assertTrue(version >= 180000 && version < 190000, "PostgreSQL 18 required");
            }
        }
        flyway.migrate();
        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(snapshotInTransaction().values().stream().allMatch(List::isEmpty),
                    "Refusing nonempty application tables; this suite never cleans them");
            Instant future = Instant.now().plusSeconds(3600);
            seed(1, 1, TherapySessionStatus.ACTIVE, future, DOCTOR, false);
            seed(2, 2, TherapySessionStatus.ACTIVE, future, DOCTOR, false);
            seed(3, 1, TherapySessionStatus.ACTIVE, CREATED.plusSeconds(600), DOCTOR, false);
            seed(4, 1, TherapySessionStatus.COMPLETED, null, OTHER_DOCTOR, false);
            seed(5, 1, TherapySessionStatus.COMPLETED, null, DOCTOR, true);
            for (TherapySessionStatus status : TherapySessionStatus.values())
                if (status.isTerminal()) seed(10 + status.ordinal(), 1, status, null, DOCTOR, false);
            seed(20, 1, TherapySessionStatus.COMPLETED, null, DOCTOR, false);
            entities.flush();
            // Insert a malformed JSON-object fixture through normal constraints/triggers, not UPDATE or trigger bypass.
            assertEquals(1, entities.createNativeQuery("INSERT INTO therapeutic_exercise_configurations "
                    + "SELECT (jsonb_populate_record(NULL::therapeutic_exercise_configurations, to_jsonb(c) || "
                    + "jsonb_build_object('id', CAST(:id AS text), 'version', 99, 'parameters', '{}'::jsonb))).* "
                    + "FROM therapeutic_exercise_configurations c WHERE c.id = :source")
                    .setParameter("id", id(25, 99)).setParameter("source", id(25, 20)).executeUpdate());
            assertEquals(1, entities.createNativeQuery("INSERT INTO therapy_sessions "
                    + "SELECT (jsonb_populate_record(NULL::therapy_sessions, to_jsonb(s) || "
                    + "jsonb_build_object('id', CAST(:id AS text), 'therapeutic_configuration_id', CAST(:configuration AS text), "
                    + "'configuration_version', 99, 'configuration_parameters', '{}'::jsonb, "
                    + "'idempotency_key', 'synthetic-visual-search-corrupt'))).* "
                    + "FROM therapy_sessions s WHERE s.id = :source")
                    .setParameter("id", id(22, 99)).setParameter("configuration", id(25, 99))
                    .setParameter("source", id(22, 20)).executeUpdate());
        });
    }

    @BeforeEach void beginObservation() {
        peers.scenario(id(21, 1), id(22, 1), "synthetic-unused-peer-token", false, false);
        before = snapshot();
    }

    @AfterEach void assertNoEffects() {
        assertTrue(before.equals(snapshot()), "Authorization request changed persisted Clinical state");
        assertEquals(new SessionReportPeersTestResource.Counts(0, 0, 0, 0, 0), peers.counts(),
                "Authorization must not call Profile or Analytics");
    }

    @Test void schemaOneUsesFrozenConfigurationDespiteNewerVersion() throws Exception {
        success(request(patientToken(1), id(22, 1).toString()), 1, true);
    }

    @Test void schemaTwoUnwrapsFrozenPrescription() throws Exception {
        success(request(patientToken(2), id(22, 2).toString()), 2, true);
    }

    @Test void expiredActiveSessionRemainsReadableWithoutRenewal() throws Exception {
        success(request(patientToken(3), id(22, 3).toString()), 3, false);
    }

    @Test void terminalSessionsPermitConfirmationButNeverInsertion() throws Exception {
        for (TherapySessionStatus status : TherapySessionStatus.values()) if (status.isTerminal()) {
            int n = 10 + status.ordinal();
            success(request(patientToken(n), id(22, n).toString()), n, false);
        }
    }

    @Test void ownershipAssignmentExerciseAndMissingSessionFailClosed() throws Exception {
        error(request(patientToken(2), id(22, 1).toString()), 403, "FORBIDDEN");
        error(request(patientToken(4), id(22, 4).toString()), 403, "FORBIDDEN");
        error(request(patientToken(5), id(22, 5).toString()), 403, "FORBIDDEN");
        error(request(patientToken(1), id(22, 100).toString()), 403, "FORBIDDEN");
    }

    @Test void corruptFrozenConfigurationIsUnavailableNotPermissionOrAbsence() throws Exception {
        error(request(patientToken(20), id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    @Test void malformedAndNoncanonicalSessionIdsAre400() throws Exception {
        for (String invalid : List.of("bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A"))
            error(request(patientToken(1), invalid), 400, "INVALID_REQUEST");
    }

    @Test void missingMalformedExpiredAndTamperedTokensAre401() {
        String valid = patientToken(1);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(1), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            Response response = request(invalid, id(22, 1).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
    }

    @Test void wrongAndMixedRolesAre403() throws Exception {
        for (List<String> groups : List.of(List.of("DOCTOR"), List.of("ADMIN"))) {
            String token = token(subject(1), groups, Instant.now().plusSeconds(300));
            Response response = request(token, id(22, 1).toString());
            assertEquals(403, response.statusCode());
            safeBody(response, token);
        }
        for (String role : List.of("DOCTOR", "ADMIN"))
            error(request(token(subject(1), List.of("PATIENT", role), Instant.now().plusSeconds(300)),
                    id(22, 1).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSummaryUsesFrozenSchemasAndAllowsClosedSessionReads() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorSuccess(doctorRequest(doctor, id(22, 1).toString()), 1);
        doctorSuccess(doctorRequest(doctor, id(22, 2).toString()), 2);
        doctorSuccess(doctorRequest(doctor, id(22, 3).toString()), 3);
        for (TherapySessionStatus status : TherapySessionStatus.values()) if (status.isTerminal()) {
            int n = 10 + status.ordinal();
            doctorSuccess(doctorRequest(doctor, id(22, n).toString()), n);
        }
    }

    @Test void doctorSummaryRequiresSessionAuthorshipAndItsOwnActiveAssignment() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String other = token(OTHER_DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorError(doctorRequest(other, id(22, 1).toString()), 403, "FORBIDDEN");
        doctorError(doctorRequest(doctor, id(22, 4).toString()), 403, "FORBIDDEN");
        // Session 4 belongs to OTHER_DOCTOR, but its patient is assigned only to DOCTOR.
        doctorError(doctorRequest(other, id(22, 4).toString()), 403, "FORBIDDEN");
        doctorError(doctorRequest(doctor, id(22, 100).toString()), 403, "FORBIDDEN");
        doctorError(doctorRequest(doctor, id(22, 5).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSummaryNativeAuthenticationAndRoleRejectionsAreSafe() throws Exception {
        String valid = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String damaged = valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature";
        for (String invalid : Arrays.asList(null, damaged)) {
            var response = doctorRequest(invalid, id(22, 1).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
        // These roles are rejected by Quarkus before the controller; do not assume its error body.
        for (List<String> roles : List.of(List.of("PATIENT"), List.of("ADMIN"))) {
            String wrong = token(DOCTOR.value(), roles, Instant.now().plusSeconds(300));
            var response = doctorRequest(wrong, id(22, 1).toString());
            assertEquals(403, response.statusCode()); safeBody(response, wrong);
        }
        // DOCTOR reaches the resource, where the exact-role check rejects mixed identities.
        for (String extra : List.of("PATIENT", "ADMIN"))
            doctorError(doctorRequest(token(DOCTOR.value(), List.of("DOCTOR", extra), Instant.now().plusSeconds(300)),
                    id(22, 1).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSummaryCorruptFrozenPolicyIsUnavailableNotPermissionOrAbsence() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorError(doctorRequest(doctor, id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    private Response doctorRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/visual-search-summary-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void doctorSuccess(Response response, int n) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "configurationId",
                "configurationVersion", "minimumFixationMs", "maximumSearchTimeMs")), "Unexpected DOCTOR authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumSearchTimeMs").isIntegralNumber() && body.path("maximumSearchTimeMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void doctorError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("VISUAL_SEARCH_SUMMARY_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed DOCTOR authorization code");
        safeBody(response, null);
    }

    private void success(Response response, int n, boolean mayInsert) throws Exception {
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "mayInsert",
                "configurationId", "configurationVersion", "minimumFixationMs", "maximumSearchTimeMs")),
                "Unexpected authorization payload fields");
        assertEquals(1, body.path("contractVersion").asInt());
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(body.path("mayInsert").isBoolean() && body.path("mayInsert").asBoolean() == mayInsert);
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertEquals(1, body.path("configurationVersion").asLong());
        assertEquals(500, body.path("minimumFixationMs").asInt());
        assertEquals(3000, body.path("maximumSearchTimeMs").asInt());
        safeBody(response, null);
    }

    private void error(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("VISUAL_SEARCH_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed code");
        safeBody(response, null);
    }

    private void safeBody(Response response, String token) {
        String body = response.asString();
        assertFalse(body.contains("synthetic-visual-search") || body.contains("Bearer ") || body.contains("eyJ"),
                "Response exposed identity or token content");
        if (token != null) assertFalse(body.contains(token), "Response exposed bearer token");
    }

    private Response request(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        Response response = request.get("/api/internal/v1/therapy-sessions/" + session + "/visual-search-result-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void seed(int n, int schema, TherapySessionStatus status, Instant lease, CognitoSubject doctor, boolean otherExercise) {
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        TherapeuticExercise exercise = otherExercise ? TherapeuticExercise.FIXATION : TherapeuticExercise.VISUAL_SEARCH;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, doctor,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        TherapeuticExerciseParameters parameters = otherExercise ? TherapeuticConfigurationTestData.fixationSnapshot().parameters()
                : new TherapeuticExerciseParameters.VisualSearch(TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                        TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
        if (schema == 2) parameters = codec.decode(exercise, 2, "{\"parameters\":" + codec.encode(parameters)
                + ",\"completionTarget\":{\"criterion\":\"REPETITIONS\",\"target\":5}}");
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, schema, CREATED, doctor, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, doctor,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-visual-search-" + n), "a".repeat(64), 1, 15, 10, status,
                0, 0, true, CREATED, lease, status.isTerminal() ? CREATED.plusSeconds(120) : null,
                status == TherapySessionStatus.ACTIVE || status == TherapySessionStatus.COMPLETED ? null : "SYNTHETIC_TERMINAL"));
        if (n == 1) {
            var newer = new TherapeuticExerciseParameters.VisualSearch(TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                    TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 1000, 6000, new BigDecimal("0.5"), 50, 1);
            configurations.append(TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, 101)),
                    plan.id(), exercise, 2, 1, CREATED.plusSeconds(1), doctor, CREATED.plusSeconds(1), newer));
        }
    }

    private Map<String, List<?>> snapshot() { return QuarkusTransaction.requiringNew().call(this::snapshotInTransaction); }
    private Map<String, List<?>> snapshotInTransaction() {
        List<?> tables = entities.createNativeQuery("SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public' "
                + "AND tablename <> 'flyway_schema_history' ORDER BY tablename").getResultList();
        Map<String, List<?>> result = new LinkedHashMap<>();
        for (Object value : tables) {
            String table = value.toString();
            assertTrue(table.matches("[a-z][a-z0-9_]*"), "Unsupported synthetic table name");
            List<?> rows = entities.createNativeQuery("SELECT CAST(to_jsonb(t) AS text) FROM \"" + table
                    + "\" t ORDER BY CAST(to_jsonb(t) AS text)").setMaxResults(2001).getResultList();
            assertTrue(rows.size() <= 2000, "Synthetic observation limit exceeded");
            result.put(table, List.copyOf(rows));
        }
        assertFalse(result.isEmpty(), "Migrated schema required");
        return result;
    }
    private static UUID id(long group, long n) { return new UUID(group, n); }
    private static String subject(int n) { return "synthetic-visual-search-patient-" + n; }
    private static String patientToken(int n) { return token(subject(n), List.of("PATIENT"), Instant.now().plusSeconds(300)); }
    private static String token(String subject, List<String> roles, Instant expiry) {
        return Jwt.subject(subject).issuer("https://server.example.com").claim("client_id", "test-client")
                .claim("token_use", "access").claim("cognito:groups", roles).expiresAt(expiry.getEpochSecond()).sign();
    }
}
