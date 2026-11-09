package com.neurovision.rehab.clinical.interfaces.rest.resource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeVisualSearchSessionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorVisualSearchSummaryQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorCompensatorySaccadeSummaryQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorSystematicExplorationSummaryQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorStandardScanningSummaryQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorSustainedFixationSummaryQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeStandardScanningSessionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeSustainedFixationSessionQueryService;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeSystematicExplorationSessionQueryService;
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
    @Inject AuthorizeDoctorStandardScanningSummaryQueryService doctorScanningAuthorization;
    @Inject AuthorizeDoctorSustainedFixationSummaryQueryService doctorFixationSummaryAuthorization;
    @Inject AuthorizeDoctorCompensatorySaccadeSummaryQueryService doctorCompensatorySummaryAuthorization;
    @Inject AuthorizeDoctorSystematicExplorationSummaryQueryService doctorSystematicSummaryAuthorization;
    @Inject AuthorizeStandardScanningSessionQueryService scanningAuthorization;
    @Inject AuthorizeSustainedFixationSessionQueryService fixationAuthorization;
    @Inject AuthorizeSystematicExplorationSessionQueryService systematicAuthorization;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URI http;
    SessionReportPeersTestResource peers;
    private Map<String, List<?>> before;
    private LocalDate availabilityFixtureDate;

    @BeforeAll void attestMigrateAndSeedDedicatedDatabase() throws Exception {
        ClinicalSessionReportHttpProfile.requireOptIn();
        assertNotNull(authorization, "Production authorization service must resolve through CDI");
        assertNotNull(doctorAuthorization, "Production DOCTOR authorization service must resolve through CDI");
        assertNotNull(doctorScanningAuthorization, "Production DOCTOR Standard Scanning authorization must resolve through CDI");
        assertNotNull(doctorFixationSummaryAuthorization, "Production DOCTOR Sustained Fixation authorization must resolve through CDI");
        assertNotNull(doctorCompensatorySummaryAuthorization,
                "Production DOCTOR Compensatory Saccade authorization service must resolve through CDI");
        assertNotNull(doctorSystematicSummaryAuthorization,
                "Production DOCTOR Systematic Exploration authorization service must resolve through CDI");
        assertNotNull(scanningAuthorization, "Production Standard Scanning authorization service must resolve through CDI");
        assertNotNull(fixationAuthorization, "Production Sustained Fixation authorization service must resolve through CDI");
        assertNotNull(systematicAuthorization, "Production Systematic Exploration authorization service must resolve through CDI");
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
            var standard = TherapeuticExerciseParameters.VisualScanningMode.STANDARD_SCANNING;
            seedScanning(40, 1, TherapySessionStatus.ACTIVE, future, standard, false);
            seedScanning(41, 2, TherapySessionStatus.ACTIVE, future, standard, false);
            seedScanning(42, 1, TherapySessionStatus.ACTIVE, CREATED.plusSeconds(600), standard, false);
            seedScanning(43, 1, TherapySessionStatus.COMPLETED, null, standard, false);
            seedScanning(44, 1, TherapySessionStatus.COMPLETED, null, standard, true);
            seedScanning(45, 1, TherapySessionStatus.ACTIVE, future,
                    TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION, false);
            seedScanning(46, 1, TherapySessionStatus.ACTIVE, future,
                    TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE, false);
            var compensatory = TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE;
            seedScanning(80, 2, TherapySessionStatus.ACTIVE, future, compensatory, false);
            seedScanning(81, 1, TherapySessionStatus.ACTIVE, CREATED.plusSeconds(600), compensatory, false);
            seedScanning(82, 1, TherapySessionStatus.COMPLETED, null, compensatory, false);
            seedScanning(83, 1, TherapySessionStatus.COMPLETED, null, compensatory, true);
            var systematic = TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION;
            seedScanning(110, 2, TherapySessionStatus.ACTIVE, future, systematic, false);
            seedScanning(111, 1, TherapySessionStatus.ACTIVE, CREATED.plusSeconds(600), systematic, false);
            seedScanning(112, 1, TherapySessionStatus.COMPLETED, null, systematic, false);
            seedScanning(113, 1, TherapySessionStatus.COMPLETED, null, systematic, true);
            seedFixation(70, 1, TherapySessionStatus.ACTIVE, future, false);
            seedFixation(71, 2, TherapySessionStatus.ACTIVE, future, false);
            seedFixation(72, 1, TherapySessionStatus.ACTIVE, CREATED.plusSeconds(600), false);
            seedFixation(73, 1, TherapySessionStatus.COMPLETED, null, false);
            seedFixation(74, 1, TherapySessionStatus.COMPLETED, null, true);
            seedDoctorScanningAuthorshipFixture();
            seedDoctorCompensatorySummaryAuthorshipFixture();
            seedDoctorSystematicSummaryAuthorshipFixture();
            seedDoctorFixationSummaryAuthorshipFixture();
            availabilityFixtureDate = Instant.now().atZone(ZoneId.of("America/Lima")).toLocalDate();
            seedDailyAvailability(150, 3);
            seedDailyAvailability(151, 1);
            seed(153, 1, TherapySessionStatus.ACTIVE, future, OTHER_DOCTOR, false);
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

    @Test void standardScanningUsesBothFrozenSchemasDespiteNewerConfiguration() throws Exception {
        scanningSuccess(scanningRequest(patientToken(40), id(22, 40).toString()), 40, true);
        scanningSuccess(scanningRequest(patientToken(41), id(22, 41).toString()), 41, true);
    }

    @Test void standardScanningClosedAndExpiredSessionsPermitOnlyConfirmationWithoutMutation() throws Exception {
        scanningSuccess(scanningRequest(patientToken(42), id(22, 42).toString()), 42, false);
        scanningSuccess(scanningRequest(patientToken(43), id(22, 43).toString()), 43, false);
    }

    @Test void standardScanningOwnershipEndedAssignmentModesAndCorruptPolicyFailClosed() throws Exception {
        scanningError(scanningRequest(patientToken(41), id(22, 40).toString()), 403, "FORBIDDEN");
        scanningError(scanningRequest(patientToken(44), id(22, 44).toString()), 403, "FORBIDDEN");
        // Existing Visual Search fixture is a different exercise, not a malformed Standard Scanning policy.
        scanningError(scanningRequest(patientToken(1), id(22, 1).toString()), 403, "FORBIDDEN");
        for (int n : List.of(45, 46))
            scanningError(scanningRequest(patientToken(n), id(22, n).toString()), 403, "FORBIDDEN");
        scanningError(scanningRequest(patientToken(40), id(22, 100).toString()), 403, "FORBIDDEN");
        // Reuse the existing corrupt frozen JSON fixture; the shared reader must fail before granting any mode.
        scanningError(scanningRequest(patientToken(20), id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    @Test void standardScanningNativeAuthenticationAndExactRoleRejectionsAreSafe() throws Exception {
        String valid = patientToken(40);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(40), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            var response = scanningRequest(invalid, id(22, 40).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
        }
        // Framework rejections are not required to use the controller's JSON body or cache header.
        for (List<String> roles : List.of(List.of("DOCTOR"), List.of("ADMIN")))
            assertEquals(403, scanningRequest(token(subject(40), roles, Instant.now().plusSeconds(300)),
                    id(22, 40).toString()).statusCode());
        for (String extra : List.of("DOCTOR", "ADMIN", "OTHER"))
            scanningError(scanningRequest(token(subject(40), List.of("PATIENT", extra), Instant.now().plusSeconds(300)),
                    id(22, 40).toString()), 403, "FORBIDDEN");
    }

    @Test void compensatorySaccadeUsesBothFrozenSchemasDespiteNewerConfiguration() throws Exception {
        compensatorySuccess(compensatoryRequest(patientToken(46), id(22, 46).toString()), 46, true);
        compensatorySuccess(compensatoryRequest(patientToken(80), id(22, 80).toString()), 80, true);
    }

    @Test void compensatorySaccadeClosedAndExpiredSessionsPermitOnlyConfirmationWithoutMutation() throws Exception {
        compensatorySuccess(compensatoryRequest(patientToken(81), id(22, 81).toString()), 81, false);
        compensatorySuccess(compensatoryRequest(patientToken(82), id(22, 82).toString()), 82, false);
    }

    @Test void compensatorySaccadeOwnershipEndedAssignmentModeAndCorruptPolicyFailClosed() throws Exception {
        compensatoryError(compensatoryRequest(patientToken(80), id(22, 46).toString()), 403, "FORBIDDEN");
        compensatoryError(compensatoryRequest(patientToken(83), id(22, 83).toString()), 403, "FORBIDDEN");
        for (int n : List.of(1, 40, 45))
            compensatoryError(compensatoryRequest(patientToken(n), id(22, n).toString()), 403, "FORBIDDEN");
        // The shared reader rejects the existing corrupt frozen JSON before granting a permit for any mode.
        compensatoryError(compensatoryRequest(patientToken(20), id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    @Test void compensatorySaccadeNativeAuthenticationAndExactRoleRejectionsAreSafe() throws Exception {
        String session = id(22, 46).toString();
        String valid = patientToken(46);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(46), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            var response = compensatoryRequest(invalid, session);
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
        }
        // Native rejections retain the framework body and cache-header contract.
        for (String role : List.of("DOCTOR", "ADMIN"))
            assertEquals(403, compensatoryRequest(token(subject(46), List.of(role), Instant.now().plusSeconds(300)),
                    session).statusCode());
        for (String extra : List.of("DOCTOR", "ADMIN", "OTHER"))
            compensatoryError(compensatoryRequest(token(subject(46), List.of("PATIENT", extra),
                    Instant.now().plusSeconds(300)), session), 403, "FORBIDDEN");
    }

    @Test void systematicExplorationUsesBothFrozenSchemasDespiteNewerConfiguration() throws Exception {
        systematicSuccess(systematicRequest(patientToken(45), id(22, 45).toString()), 45, true);
        systematicSuccess(systematicRequest(patientToken(110), id(22, 110).toString()), 110, true);
    }

    @Test void systematicExplorationClosedAndExpiredSessionsPermitOnlyConfirmationWithoutMutation() throws Exception {
        systematicSuccess(systematicRequest(patientToken(111), id(22, 111).toString()), 111, false);
        systematicSuccess(systematicRequest(patientToken(112), id(22, 112).toString()), 112, false);
    }

    @Test void systematicExplorationOwnershipEndedAssignmentModeAndCorruptPolicyFailClosed() throws Exception {
        systematicError(systematicRequest(patientToken(110), id(22, 45).toString()), 403, "FORBIDDEN");
        systematicError(systematicRequest(patientToken(113), id(22, 113).toString()), 403, "FORBIDDEN");
        for (int n : List.of(1, 40, 46))
            systematicError(systematicRequest(patientToken(n), id(22, n).toString()), 403, "FORBIDDEN");
        // The shared reader rejects the existing corrupt frozen JSON before granting a permit for any mode.
        systematicError(systematicRequest(patientToken(20), id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    @Test void systematicExplorationNativeAuthenticationAndExactRoleRejectionsAreSafe() throws Exception {
        String session = id(22, 45).toString();
        String valid = patientToken(45);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(45), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            var response = systematicRequest(invalid, session);
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
        }
        // Native rejections retain the framework body and cache-header contract.
        for (String role : List.of("DOCTOR", "ADMIN"))
            assertEquals(403, systematicRequest(token(subject(45), List.of(role), Instant.now().plusSeconds(300)),
                    session).statusCode());
        for (String extra : List.of("DOCTOR", "ADMIN", "OTHER"))
            systematicError(systematicRequest(token(subject(45), List.of("PATIENT", extra),
                    Instant.now().plusSeconds(300)), session), 403, "FORBIDDEN");
    }

    @Test void doctorStandardScanningSummaryUsesFrozenSchemasAndAllowsClosedSessionReads() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (int n : List.of(40, 41, 42, 43))
            doctorScanningSuccess(doctorScanningRequest(doctor, id(22, n).toString()), n);
    }

    @Test void doctorStandardScanningSummaryRequiresSessionAuthorshipAndItsOwnActiveAssignment() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String other = token(OTHER_DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorScanningError(doctorScanningRequest(other, id(22, 40).toString()), 403, "FORBIDDEN");
        // Session 47 belongs to OTHER_DOCTOR; its patient is assigned only to DOCTOR.
        doctorScanningError(doctorScanningRequest(doctor, id(22, 47).toString()), 403, "FORBIDDEN");
        doctorScanningError(doctorScanningRequest(other, id(22, 47).toString()), 403, "FORBIDDEN");
        doctorScanningError(doctorScanningRequest(doctor, id(22, 44).toString()), 403, "FORBIDDEN");
        doctorScanningError(doctorScanningRequest(doctor, id(22, 100).toString()), 403, "FORBIDDEN");
        doctorScanningError(doctorScanningRequest(doctor, id(22, 1).toString()), 403, "FORBIDDEN");
        for (int n : List.of(45, 46))
            doctorScanningError(doctorScanningRequest(doctor, id(22, n).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorStandardScanningSummaryNativeAuthenticationAndRoleRejectionsAreSafe() throws Exception {
        String valid = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            var response = doctorScanningRequest(invalid, id(22, 40).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
        // Framework rejections retain their own response body and cache-header contract.
        for (List<String> roles : List.of(List.of("PATIENT"), List.of("ADMIN"))) {
            String wrong = token(DOCTOR.value(), roles, Instant.now().plusSeconds(300));
            var response = doctorScanningRequest(wrong, id(22, 40).toString());
            assertEquals(403, response.statusCode()); safeBody(response, wrong);
        }
        for (String extra : List.of("PATIENT", "ADMIN"))
            doctorScanningError(doctorScanningRequest(token(DOCTOR.value(), List.of("DOCTOR", extra), Instant.now().plusSeconds(300)),
                    id(22, 40).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorStandardScanningSummaryCorruptFrozenPolicyIsUnavailableNotPermissionOrAbsence() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        // The existing malformed frozen JSON must fail in the shared reader, before granting any modality.
        doctorScanningError(doctorScanningRequest(doctor, id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    private Response doctorScanningRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/standard-scanning-summary-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void doctorScanningSuccess(Response response, int n) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "configurationId",
                "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")), "Unexpected DOCTOR authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void doctorScanningError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("STANDARD_SCANNING_SUMMARY_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed DOCTOR authorization code");
        safeBody(response, null);
    }

    private void seedDoctorScanningAuthorshipFixture() {
        int n = 47;
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        var exercise = TherapeuticExercise.VISUAL_SCANNING;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, OTHER_DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        var parameters = new TherapeuticExerciseParameters.VisualScanning(
                TherapeuticExerciseParameters.VisualScanningMode.STANDARD_SCANNING,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, 1, CREATED, OTHER_DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, OTHER_DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-doctor-scanning-summary-authorship-47"), "a".repeat(64), 1, 15, 10,
                TherapySessionStatus.COMPLETED, 0, 0, true, CREATED, null, CREATED.plusSeconds(120), null));
    }

    @Test void doctorSustainedFixationSummaryUsesFrozenSchemasAndAllowsClosedSessionReads() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (int n : List.of(70, 71, 72, 73))
            doctorFixationSummarySuccess(doctorFixationSummaryRequest(doctor, id(22, n).toString()), n);
    }

    @Test void doctorSustainedFixationSummaryRequiresSessionAuthorshipAndItsOwnActiveAssignment() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String other = token(OTHER_DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorFixationSummaryError(doctorFixationSummaryRequest(other, id(22, 70).toString()), 403, "FORBIDDEN");
        // Session 75 belongs to OTHER_DOCTOR; its patient is assigned only to DOCTOR.
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 75).toString()), 403, "FORBIDDEN");
        doctorFixationSummaryError(doctorFixationSummaryRequest(other, id(22, 75).toString()), 403, "FORBIDDEN");
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 74).toString()), 403, "FORBIDDEN");
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 100).toString()), 403, "FORBIDDEN");
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 1).toString()), 403, "FORBIDDEN");
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 40).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSustainedFixationSummaryNativeAuthenticationAndRoleRejectionsAreSafe() throws Exception {
        String valid = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            var response = doctorFixationSummaryRequest(invalid, id(22, 70).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
        // Framework rejections retain their own response body and cache-header contract.
        for (List<String> roles : List.of(List.of("PATIENT"), List.of("ADMIN"))) {
            String wrong = token(DOCTOR.value(), roles, Instant.now().plusSeconds(300));
            var response = doctorFixationSummaryRequest(wrong, id(22, 70).toString());
            assertEquals(403, response.statusCode()); safeBody(response, wrong);
        }
        for (String extra : List.of("PATIENT", "ADMIN"))
            doctorFixationSummaryError(doctorFixationSummaryRequest(token(DOCTOR.value(), List.of("DOCTOR", extra), Instant.now().plusSeconds(300)),
                    id(22, 70).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSustainedFixationSummaryCorruptFrozenPolicyIsUnavailableNotPermissionOrAbsence() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        // The existing malformed frozen JSON must fail in the shared reader, before granting any modality.
        doctorFixationSummaryError(doctorFixationSummaryRequest(doctor, id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    private Response doctorFixationSummaryRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/sustained-fixation-summary-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void doctorFixationSummarySuccess(Response response, int n) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "configurationId",
                "configurationVersion", "minimumFixationMs", "maximumFixationMs")), "Unexpected DOCTOR authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumFixationMs").isIntegralNumber() && body.path("maximumFixationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void doctorFixationSummaryError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("SUSTAINED_FIXATION_SUMMARY_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed DOCTOR authorization code");
        safeBody(response, null);
    }

    private void seedDoctorFixationSummaryAuthorshipFixture() {
        int n = 75;
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        var exercise = TherapeuticExercise.FIXATION;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, OTHER_DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        var parameters = fixationParameters(500, 3000);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, 1, CREATED, OTHER_DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, OTHER_DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-doctor-fixation-summary-authorship-75"), "a".repeat(64), 1, 15, 10,
                TherapySessionStatus.COMPLETED, 0, 0, true, CREATED, null, CREATED.plusSeconds(120), null));
    }

    @Test void doctorCompensatorySaccadeSummaryUsesFrozenSchemasAndAllowsClosedSessionReads() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (int n : List.of(46, 80, 81, 82))
            doctorCompensatorySummarySuccess(doctorCompensatorySummaryRequest(doctor, id(22, n).toString()), n);
    }

    @Test void doctorCompensatorySaccadeSummaryRequiresSessionAuthorshipAndItsOwnActiveAssignment() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String other = token(OTHER_DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(other, id(22, 46).toString()), 403, "FORBIDDEN");
        // Session 84 belongs to OTHER_DOCTOR; its patient is assigned only to DOCTOR.
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(doctor, id(22, 84).toString()), 403, "FORBIDDEN");
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(other, id(22, 84).toString()), 403, "FORBIDDEN");
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(doctor, id(22, 83).toString()), 403, "FORBIDDEN");
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(doctor, id(22, 1).toString()), 403, "FORBIDDEN");
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(doctor, id(22, 40).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorCompensatorySaccadeSummaryNativeAuthenticationAndRoleRejectionsAreSafe() throws Exception {
        String valid = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            var response = doctorCompensatorySummaryRequest(invalid, id(22, 46).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
        // Framework rejections retain their own response body and cache-header contract.
        for (List<String> roles : List.of(List.of("PATIENT"), List.of("ADMIN"))) {
            String wrong = token(DOCTOR.value(), roles, Instant.now().plusSeconds(300));
            var response = doctorCompensatorySummaryRequest(wrong, id(22, 46).toString());
            assertEquals(403, response.statusCode()); safeBody(response, wrong);
        }
        for (String extra : List.of("PATIENT", "ADMIN"))
            doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(token(DOCTOR.value(), List.of("DOCTOR", extra), Instant.now().plusSeconds(300)),
                    id(22, 46).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorCompensatorySaccadeSummaryCorruptFrozenPolicyIsUnavailableNotPermissionOrAbsence() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        // The existing malformed frozen JSON must fail in the shared reader, before granting any modality.
        doctorCompensatorySummaryError(doctorCompensatorySummaryRequest(doctor, id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    private Response doctorCompensatorySummaryRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/compensatory-saccade-summary-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void doctorCompensatorySummarySuccess(Response response, int n) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "configurationId",
                "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")), "Unexpected DOCTOR authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void doctorCompensatorySummaryError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("COMPENSATORY_SACCADE_SUMMARY_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed DOCTOR authorization code");
        safeBody(response, null);
    }

    private void seedDoctorCompensatorySummaryAuthorshipFixture() {
        int n = 84;
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        var exercise = TherapeuticExercise.VISUAL_SCANNING;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, OTHER_DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        var parameters = new TherapeuticExerciseParameters.VisualScanning(
                TherapeuticExerciseParameters.VisualScanningMode.COMPENSATORY_SACCADE,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, 1, CREATED, OTHER_DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, OTHER_DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-doctor-compensatory-summary-authorship-84"), "a".repeat(64), 1, 15, 10,
                TherapySessionStatus.COMPLETED, 0, 0, true, CREATED, null, CREATED.plusSeconds(120), null));
    }

    @Test void sustainedFixationUsesBothFrozenSchemasDespiteNewerConfiguration() throws Exception {
        fixationSuccess(fixationRequest(patientToken(70), id(22, 70).toString()), 70, true);
        fixationSuccess(fixationRequest(patientToken(71), id(22, 71).toString()), 71, true);
    }

    @Test void sustainedFixationClosedAndExpiredSessionsPermitOnlyConfirmationWithoutMutation() throws Exception {
        fixationSuccess(fixationRequest(patientToken(72), id(22, 72).toString()), 72, false);
        fixationSuccess(fixationRequest(patientToken(73), id(22, 73).toString()), 73, false);
    }

    @Test void sustainedFixationOwnershipEndedAssignmentModalityAndCorruptPolicyFailClosed() throws Exception {
        fixationError(fixationRequest(patientToken(71), id(22, 70).toString()), 403, "FORBIDDEN");
        fixationError(fixationRequest(patientToken(74), id(22, 74).toString()), 403, "FORBIDDEN");
        fixationError(fixationRequest(patientToken(1), id(22, 1).toString()), 403, "FORBIDDEN");
        fixationError(fixationRequest(patientToken(70), id(22, 100).toString()), 403, "FORBIDDEN");
        // Reuse the existing corrupt frozen JSON fixture; the shared reader must fail before granting any mode.
        fixationError(fixationRequest(patientToken(20), id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    @Test void sustainedFixationNativeAuthenticationAndExactRoleRejectionsAreSafe() throws Exception {
        String valid = patientToken(70);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(70), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            var response = fixationRequest(invalid, id(22, 70).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
        }
        // Framework rejections are not required to use the controller's JSON body or cache header.
        for (List<String> roles : List.of(List.of("DOCTOR"), List.of("ADMIN")))
            assertEquals(403, fixationRequest(token(subject(70), roles, Instant.now().plusSeconds(300)),
                    id(22, 70).toString()).statusCode());
        for (String extra : List.of("DOCTOR", "ADMIN", "OTHER"))
            fixationError(fixationRequest(token(subject(70), List.of("PATIENT", extra), Instant.now().plusSeconds(300)),
                    id(22, 70).toString()), 403, "FORBIDDEN");
    }

    private Response fixationRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/sustained-fixation-result-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void fixationSuccess(Response response, int n, boolean mayInsert) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "mayInsert",
                "configurationId", "configurationVersion", "minimumFixationMs", "maximumFixationMs")),
                "Unexpected Sustained Fixation authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(body.path("mayInsert").isBoolean() && body.path("mayInsert").asBoolean() == mayInsert);
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumFixationMs").isIntegralNumber() && body.path("maximumFixationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void fixationError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("SUSTAINED_FIXATION_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed Sustained Fixation authorization code");
        safeBody(response, null);
    }

    private void seedFixation(int n, int schema, TherapySessionStatus status, Instant lease,
            boolean endedAssignment) {
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.rehydrate(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR,
                endedAssignment ? AssignmentStatus.ENDED : AssignmentStatus.ACTIVE, CREATED,
                endedAssignment ? CREATED.plusSeconds(1) : null));
        var exercise = TherapeuticExercise.FIXATION;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        TherapeuticExerciseParameters parameters = fixationParameters(500, 3000);
        if (schema == 2) parameters = new TherapeuticExerciseParameters.Prescribed(parameters,
                new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5));
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, schema, CREATED, DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-sustained-fixation-" + n), "a".repeat(64), 1, 15, 10, status,
                0, 0, true, CREATED, lease, status.isTerminal() ? CREATED.plusSeconds(120) : null, null));
        if (n == 70 || n == 71) {
            TherapeuticExerciseParameters newer = fixationParameters(1000, 4000);
            if (schema == 2) newer = new TherapeuticExerciseParameters.Prescribed(newer,
                    new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 10));
            configurations.append(TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n + 200)),
                    plan.id(), exercise, 2, schema, CREATED.plusSeconds(1), DOCTOR, CREATED.plusSeconds(1), newer));
        }
    }

    private static TherapeuticExerciseParameters.Fixation fixationParameters(int minimumMs, int maximumMs) {
        return new TherapeuticExerciseParameters.Fixation(
                TherapeuticExerciseParameters.FixationMode.SUSTAINED_TARGET_FIXATION,
                TherapeuticExerciseParameters.TargetPosition.CENTER, minimumMs, maximumMs, 50, 5, 500, 1);
    }

    private Response scanningRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/standard-scanning-result-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void scanningSuccess(Response response, int n, boolean mayInsert) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "mayInsert",
                "configurationId", "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")),
                "Unexpected Standard Scanning authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(body.path("mayInsert").isBoolean() && body.path("mayInsert").asBoolean() == mayInsert);
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void scanningError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("STANDARD_SCANNING_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed Standard Scanning authorization code");
        safeBody(response, null);
    }

    private Response compensatoryRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/compensatory-saccade-result-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void compensatorySuccess(Response response, int n, boolean mayInsert) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "mayInsert",
                "configurationId", "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")),
                "Unexpected Compensatory Saccade authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(body.path("mayInsert").isBoolean() && body.path("mayInsert").asBoolean() == mayInsert);
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void compensatoryError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("COMPENSATORY_SACCADE_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed Compensatory Saccade authorization code");
        safeBody(response, null);
    }

    private Response systematicRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/systematic-exploration-result-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void systematicSuccess(Response response, int n, boolean mayInsert) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "mayInsert",
                "configurationId", "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")),
                "Unexpected Systematic Exploration authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(body.path("mayInsert").isBoolean() && body.path("mayInsert").asBoolean() == mayInsert);
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void systematicError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("SYSTEMATIC_EXPLORATION_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed Systematic Exploration authorization code");
        safeBody(response, null);
    }

    private void seedScanning(int n, int schema, TherapySessionStatus status, Instant lease,
            TherapeuticExerciseParameters.VisualScanningMode mode, boolean endedAssignment) {
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.rehydrate(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR,
                endedAssignment ? AssignmentStatus.ENDED : AssignmentStatus.ACTIVE, CREATED,
                endedAssignment ? CREATED.plusSeconds(1) : null));
        var exercise = TherapeuticExercise.VISUAL_SCANNING;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        TherapeuticExerciseParameters parameters = new TherapeuticExerciseParameters.VisualScanning(mode,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
        if (schema == 2) parameters = new TherapeuticExerciseParameters.Prescribed(parameters,
                new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5));
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, schema, CREATED, DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-visual-search-scanning-" + n), "a".repeat(64), 1, 15, 10, status,
                0, 0, true, CREATED, lease, status.isTerminal() ? CREATED.plusSeconds(120) : null, null));
        if (n == 40 || n == 41 || n == 46 || n == 80 || n == 45 || n == 110) {
            TherapeuticExerciseParameters newer = new TherapeuticExerciseParameters.VisualScanning(mode,
                    TherapeuticExerciseParameters.AffectedHemifield.LEFT, 1000, 6000, 10, 50, 5, 500, 1);
            if (schema == 2) newer = new TherapeuticExerciseParameters.Prescribed(newer,
                    new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 10));
            configurations.append(TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n + 200)),
                    plan.id(), exercise, 2, schema, CREATED.plusSeconds(1), DOCTOR, CREATED.plusSeconds(1), newer));
        }
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


    @Test void patientSessionDetailReadsFrozenLegacyAndPrescribedConfiguration() throws Exception {
        for (int n : List.of(1, 2)) patientSessionDetailSuccess(n, "ACTIVE", n == 1 ? 1 : 2);
    }

    @Test void patientSessionDetailRetainsTerminalAndExpiredFactsAlongsideCurrentRoute() throws Exception {
        // Fixture 4 intentionally has a different session doctor; fixture 20 has a matching assignment.
        patientSessionDetailSuccess(20, "COMPLETED", 1);
        currentSessionSuccess(20, "NONE");
        patientSessionDetailSuccess(3, "ACTIVE", 1);
        currentSessionSuccess(3, "EXPIRED");
        currentSessionSuccess(1, "ACTIVE");
    }

    @Test void patientSessionDetailRequiresOwnershipAndCurrentDoctorAssignment() throws Exception {
        patientSessionDetailError(patientSessionDetailRequest(patientToken(1), id(22, 2).toString()), 403, "FORBIDDEN");
        patientSessionDetailError(patientSessionDetailRequest(patientToken(1), id(22, 999).toString()), 403, "FORBIDDEN");
        patientSessionDetailError(patientSessionDetailRequest(patientToken(999), id(22, 1).toString()), 403, "FORBIDDEN");
        for (int n : List.of(4, 153))
            patientSessionDetailError(patientSessionDetailRequest(patientToken(n), id(22, n).toString()), 403, "FORBIDDEN");
    }

    @Test void patientSessionDetailPreservesNativeRejectionsAndValidatesIdentifiers() throws Exception {
        String valid = patientToken(1);
        for (String id : List.of("not-a-uuid", "1-1-1-1-1"))
            patientSessionDetailError(patientSessionDetailRequest(valid, id), 400, "INVALID_REQUEST");
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(1), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            Response response = patientSessionDetailRequest(invalid, id(22, 1).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Native authentication rejection must advertise Bearer");
            // Preserve native interception bodies and headers; resource contracts do not apply here.
        }
        for (List<String> roles : List.of(List.of("DOCTOR"), List.of("ADMIN"))) {
            Response response = patientSessionDetailRequest(token(subject(1), roles, Instant.now().plusSeconds(300)),
                    id(22, 1).toString());
            assertEquals(403, response.statusCode());
        }
        for (String role : List.of("DOCTOR", "ADMIN"))
            patientSessionDetailError(patientSessionDetailRequest(token(subject(1), List.of("PATIENT", role),
                    Instant.now().plusSeconds(300)), id(22, 1).toString()), 403, "FORBIDDEN");
    }

    private Response patientSessionDetailRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        Response response = request.get("/api/v1/me/therapy-sessions/" + session);
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private JsonNode patientSessionDetailSuccess(int n, String status, int schema) throws Exception {
        Response response = patientSessionDetailRequest(patientToken(n), id(22, n).toString());
        assertEquals(200, response.statusCode());
        assertTrue(response.contentType() != null && response.contentType().startsWith("application/json"));
        assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = currentSessionJson(mapper, response.asString());
        var fields = new HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(body.isObject() && fields.equals(Set.of("therapySessionId", "therapyPlanId", "exerciseCode", "status",
                "startedAt", "endedAt", "activeDurationSeconds", "quotaDate", "configurationId", "configurationVersion",
                "parameterSchemaVersion", "parameters", "sessionLimits", "technicalProgress")),
                "Expected only the fourteen own-session detail fields");
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Unexpected own session binding");
        assertTrue(id(24, n).toString().equals(body.path("therapyPlanId").asText()), "Unexpected frozen plan binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Unexpected frozen configuration binding");
        assertEquals("visual-search", body.path("exerciseCode").asText()); assertEquals(status, body.path("status").asText());
        assertTrue(CREATED.equals(Instant.parse(body.path("startedAt").asText())), "Unexpected frozen start instant");
        if (status.equals("ACTIVE")) assertTrue(body.path("endedAt").isNull(), "An ACTIVE session must remain open");
        else assertTrue(CREATED.plusSeconds(120).equals(Instant.parse(body.path("endedAt").asText())), "Unexpected terminal instant");
        assertEquals(0, body.path("activeDurationSeconds").asLong());
        assertEquals(CREATED.atZone(ZoneOffset.UTC).toLocalDate().toString(), body.path("quotaDate").asText());
        assertEquals(1, body.path("configurationVersion").asLong()); assertEquals(schema, body.path("parameterSchemaVersion").asInt());
        TherapeuticExerciseParameters frozen = new TherapeuticExerciseParameters.VisualSearch(
                TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
        if (schema == 2) frozen = new TherapeuticExerciseParameters.Prescribed(frozen,
                new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5));
        assertTrue(currentSessionJson(mapper, mapper.writeValueAsString(frozen)).equals(body.path("parameters")),
                "Detail must retain the complete frozen parameters, not a newer configuration");
        JsonNode limits = body.path("sessionLimits"); fields.clear(); limits.fieldNames().forEachRemaining(fields::add);
        assertTrue(limits.isObject() && fields.equals(Set.of("minimumValidDurationMinutes", "maximumSessionDurationMinutes", "minimumValidSampleCount")));
        assertEquals(1, limits.path("minimumValidDurationMinutes").asInt());
        assertEquals(15, limits.path("maximumSessionDurationMinutes").asInt()); assertEquals(10, limits.path("minimumValidSampleCount").asLong());
        JsonNode progress = body.path("technicalProgress");
        if (schema == 1) assertTrue(progress.isNull(), "Legacy progress must be explicit null, not inferred zeroes");
        else {
            fields.clear(); progress.fieldNames().forEachRemaining(fields::add);
            assertTrue(progress.isObject() && fields.equals(Set.of("criterion", "target", "activeMilliseconds", "completedRepetitions", "targetReached")));
            assertEquals("REPETITIONS", progress.path("criterion").asText()); assertEquals(5, progress.path("target").asLong());
            assertEquals(0, progress.path("activeMilliseconds").asLong()); assertEquals(0, progress.path("completedRepetitions").asLong());
            assertTrue(progress.path("targetReached").isBoolean() && !progress.path("targetReached").asBoolean(),
                    "A prescribed session without activity has not reached its goal");
        }
        return body;
    }

    private void patientSessionDetailError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode());
        assertTrue(response.contentType() != null && response.contentType().startsWith("application/json"));
        assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1 && ("PATIENT_SESSION_DETAIL_" + suffix).equals(body.path("code").asText()),
                "Resource rejection must contain only its fixed own-session detail code");
    }

    @Test void currentSessionUsesFrozenLegacyAndPrescribedConfiguration() throws Exception {
        for (int n : List.of(1, 2)) {
            JsonNode body = currentSessionSuccess(n, "ACTIVE");
            JsonNode session = body.path("session");
            assertEquals(n == 1 ? 1 : 2, session.path("parameterSchemaVersion").asInt());
            assertEquals(1, session.path("configurationVersion").asLong());
            TherapeuticExerciseParameters frozen = new TherapeuticExerciseParameters.VisualSearch(
                    TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                    TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000,
                    new BigDecimal("0.5"), 50, 1);
            if (n == 2) frozen = new TherapeuticExerciseParameters.Prescribed(frozen,
                    new CompletionTarget(CompletionTarget.Criterion.REPETITIONS, 5));
            JsonNode expected = currentSessionJson(mapper, mapper.writeValueAsString(frozen));
            assertTrue(expected.equals(session.path("parameters")),
                    "The frozen parameters must be returned, not the newer configuration");
            assertTrue(Instant.parse(session.path("leaseExpiresAt").asText())
                    .isAfter(Instant.parse(body.path("evaluatedAt").asText())), "Expected a live lease");
        }
    }

    @Test void currentSessionObservesExpiredAndAbsentWithoutReconciliation() throws Exception {
        JsonNode expired = currentSessionSuccess(3, "EXPIRED");
        assertTrue(CREATED.plusSeconds(600).equals(
                Instant.parse(expired.path("session").path("leaseExpiresAt").asText())),
                "The expired historical lease must remain unchanged");
        assertTrue(!Instant.parse(expired.path("evaluatedAt").asText()).isBefore(
                Instant.parse(expired.path("session").path("leaseExpiresAt").asText())));
        JsonNode absent = currentSessionSuccess(4, "NONE");
        assertTrue(absent.path("session").isNull(), "A terminal-only session is not current");
    }

    @Test void currentSessionRequiresOwnPatientAndCurrentDoctorAssignment() throws Exception {
        for (int n : List.of(1, 2)) {
            JsonNode body = currentSessionSuccess(n, "ACTIVE");
            assertTrue(id(22, n).toString().equals(body.path("session").path("therapySessionId").asText()),
                    "The signed patient's own session must be selected");
        }
        currentSessionError(currentSessionRequest(patientToken(999)), 403, "FORBIDDEN");
        currentSessionError(currentSessionRequest(patientToken(153)), 403, "FORBIDDEN");
    }

    @Test void currentSessionPreservesNativeAuthenticationAndRejectsPrivilegedMixedRoles() throws Exception {
        String valid = patientToken(1);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(1), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            Response response = currentSessionRequest(invalid);
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            // Native interception does not inherit the resource's JSON or cache-header contract.
        }
        for (List<String> roles : List.of(List.of("DOCTOR"), List.of("ADMIN"))) {
            Response response = currentSessionRequest(token(subject(1), roles, Instant.now().plusSeconds(300)));
            assertEquals(403, response.statusCode());
        }
        for (String role : List.of("DOCTOR", "ADMIN")) {
            currentSessionError(currentSessionRequest(token(subject(1), List.of("PATIENT", role),
                    Instant.now().plusSeconds(300))), 403, "FORBIDDEN");
        }
    }

    private Response currentSessionRequest(String token) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        Response response = request.get("/api/v1/me/therapy-sessions/current");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private JsonNode currentSessionSuccess(int n, String state) throws Exception {
        Instant started = Instant.now();
        Response response = currentSessionRequest(patientToken(n));
        Instant finished = Instant.now();
        assertEquals(200, response.statusCode());
        assertTrue(response.contentType() != null && response.contentType().startsWith("application/json"));
        assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = currentSessionJson(mapper, response.asString());
        assertTrue(body.isObject() && body.size() == 3 && body.has("state")
                && body.has("evaluatedAt") && body.has("session"), "Expected the three-field current-session response");
        assertEquals(state, body.path("state").asText());
        assertTrue(body.path("evaluatedAt").asText().endsWith("Z"), "Expected a UTC observation instant");
        Instant evaluated = Instant.parse(body.path("evaluatedAt").asText());
        assertTrue(!evaluated.isBefore(started) && !evaluated.isAfter(finished), "Expected this request's observation instant");
        if (state.equals("NONE")) {
            assertTrue(body.path("session").isNull());
            return body;
        }
        JsonNode session = body.path("session");
        var fields = new HashSet<String>(); session.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("therapySessionId", "therapyPlanId", "exerciseCode", "status", "quotaDate",
                "configurationId", "configurationVersion", "parameterSchemaVersion", "parameters",
                "minimumValidDurationMinutes", "maximumSessionDurationMinutes", "minimumValidSampleCount",
                "startedAt", "leaseExpiresAt")), "Expected the existing fourteen-field start payload");
        assertTrue(id(22, n).toString().equals(session.path("therapySessionId").asText()), "Unexpected session binding");
        assertTrue(id(24, n).toString().equals(session.path("therapyPlanId").asText()), "Unexpected plan binding");
        assertTrue(id(25, n).toString().equals(session.path("configurationId").asText()), "Unexpected frozen configuration binding");
        assertEquals("visual-search", session.path("exerciseCode").asText());
        assertEquals("ACTIVE", session.path("status").asText());
        assertEquals(CREATED.atZone(ZoneOffset.UTC).toLocalDate().toString(), session.path("quotaDate").asText());
        assertTrue(CREATED.equals(Instant.parse(session.path("startedAt").asText())), "Unexpected frozen start instant");
        assertEquals(1, session.path("minimumValidDurationMinutes").asInt());
        assertEquals(15, session.path("maximumSessionDurationMinutes").asInt());
        assertEquals(10, session.path("minimumValidSampleCount").asLong());
        return body;
    }

    private void currentSessionError(Response response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("PATIENT_CURRENT_SESSION_" + code).equals(body.path("code").asText()),
                "Resource failure must contain only its fixed current-session code");
    }

    static JsonNode currentSessionJson(ObjectMapper mapper, String json) throws java.io.IOException {
        // Normalize both wire representations locally; do not mutate the injected shared mapper.
        return mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(json);
    }

    @Test void dailyAvailabilityUsesPersistedQuotaFlagsAndLocalDate() throws Exception {
        for (int n : List.of(150, 151)) {
            Instant started = Instant.now();
            Response response = availabilityRequest(patientToken(n));
            Instant finished = Instant.now();
            assertEquals(200, response.statusCode());
            JsonNode rows = mapper.readTree(response.asString());
            assertTrue(rows.isArray() && rows.size() == 1, "Only the eligible plan must be listed");
            JsonNode plan = rows.get(0);
            assertEquals(id(24, n).toString(), plan.path("therapyPlanId").asText(), "Unexpected plan binding");
            assertEquals("ACTIVE", plan.path("status").asText());
            assertEquals(n == 150 ? 3 : 1, plan.path("dailySessionLimit").asInt());
            assertEquals(4, plan.path("dailyExercisePrescriptions").path("visual-search").asInt());
            JsonNode configurations = plan.path("effectiveConfigurations");
            assertTrue(configurations.isArray() && configurations.size() == 1);
            assertEquals(id(25, n).toString(), configurations.get(0).path("configurationId").asText(),
                    "Unexpected effective configuration binding");
            assertEquals(1, configurations.get(0).path("version").asLong());
            JsonNode availability = plan.path("dailyAvailability");
            assertAvailabilityDate(availability, started, finished);
            assertTrue(availability.path("remainingPlanSessionSlots").isIntegralNumber());
            assertEquals(n == 150 ? 2 : 0, availability.path("remainingPlanSessionSlots").asLong());
            JsonNode exercises = availability.path("exercises");
            assertTrue(exercises.isArray() && exercises.size() == 1);
            JsonNode exercise = exercises.get(0);
            assertEquals("visual-search", exercise.path("exerciseCode").asText());
            assertTrue(exercise.path("remainingPrescribedSlots").isIntegralNumber());
            assertEquals(3, exercise.path("remainingPrescribedSlots").asLong());
            assertEquals(n == 150 ? "AVAILABLE" : "BLOCKED", exercise.path("status").asText());
            if (n == 150) assertTrue(exercise.path("reason").isNull());
            else assertEquals("PLAN_QUOTA_EXHAUSTED", exercise.path("reason").asText());
        }
    }

    @Test void dailyAvailabilityDefersExpiredActiveWithoutReconciliation() throws Exception {
        Instant started = Instant.now();
        Response response = availabilityRequest(patientToken(3));
        Instant finished = Instant.now();
        assertEquals(200, response.statusCode());
        JsonNode rows = mapper.readTree(response.asString());
        assertTrue(rows.isArray() && rows.size() == 1);
        JsonNode availability = rows.get(0).path("dailyAvailability");
        assertAvailabilityDate(availability, started, finished);
        assertTrue(availability.path("remainingPlanSessionSlots").isNull());
        JsonNode exercises = availability.path("exercises");
        assertTrue(exercises.isArray() && exercises.size() == 1);
        assertEquals("CHECK_AT_START", exercises.get(0).path("status").asText());
        assertTrue(exercises.get(0).path("remainingPrescribedSlots").isNull());
        assertTrue(exercises.get(0).path("reason").isNull());
        // The shared full-row snapshot also pins the expired session's ACTIVE status and lease.
    }

    @Test void dailyAvailabilityPreservesOwnershipAssignmentAndEmptyList() throws Exception {
        Response own = availabilityRequest(patientToken(150));
        assertEquals(200, own.statusCode());
        JsonNode rows = mapper.readTree(own.asString());
        assertTrue(rows.isArray() && rows.size() == 1,
                "Foreign, suspended and unassigned plans must not be disclosed");
        assertEquals(id(24, 150).toString(), rows.get(0).path("therapyPlanId").asText(), "Unexpected plan binding");
        Response unassigned = availabilityRequest(patientToken(4));
        assertEquals(200, unassigned.statusCode());
        JsonNode empty = mapper.readTree(unassigned.asString());
        assertTrue(empty.isArray() && empty.isEmpty());
        assertEquals(403, availabilityRequest(patientToken(999)).statusCode());
    }

    @Test void dailyAvailabilityPreservesNativeAuthenticationAndExistingRolePolicy() {
        String valid = patientToken(150);
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(subject(150), List.of("PATIENT"), Instant.now().minusSeconds(120)))) {
            Response response = availabilityRequest(invalid);
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
        }
        for (List<String> roles : List.of(List.of("DOCTOR"), List.of("ADMIN"), List.of("PATIENT", "DOCTOR"))) {
            assertEquals(403, availabilityRequest(token(subject(150), roles,
                    Instant.now().plusSeconds(300))).statusCode());
        }
        // Do not impose another resource's fixed JSON or cache-header policy on native errors.
    }

    private Response availabilityRequest(String token) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        Response response = request.get("/api/v1/me/therapy-plans");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void assertAvailabilityDate(JsonNode availability, Instant started, Instant finished) {
        assertTrue(availability.isObject());
        Instant evaluated = Instant.parse(availability.path("evaluatedAt").asText());
        assertTrue(!evaluated.isBefore(started) && !evaluated.isAfter(finished),
                "Evaluation must use the current request's server instant");
        LocalDate date = LocalDate.parse(availability.path("quotaDate").asText());
        assertTrue(date.equals(evaluated.atZone(ZoneId.of("America/Lima")).toLocalDate()),
                "Quota date must use America/Lima");
        assertTrue(date.equals(availabilityFixtureDate) || date.equals(availabilityFixtureDate.plusDays(1)),
                "Synthetic accounting fixtures are outside their prepared date window");
    }

    private void seedDailyAvailability(int n, int limit) {
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        var exercise = TherapeuticExercise.VISUAL_SEARCH;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, limit, 30, null, 1, 15, 10, Map.of(exercise.code(), 4));
        plans.save(plan);
        var parameters = new TherapeuticExerciseParameters.VisualSearch(
                TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, 1, CREATED, DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        // Equal accounting on both days avoids a midnight race without sleeping or replacing Clock.
        for (int day = -1; day <= 1; day++) {
            LocalDate date = availabilityFixtureDate.plusDays(day);
            Instant start = date.atStartOfDay(ZoneId.of("America/Lima")).toInstant();
            for (int entry = 0; entry < 2; entry++) {
                int serial = n * 10 + (day + 1) * 2 + entry;
                sessions.save(TherapySession.rehydrate(new TherapySessionId(id(160, serial)), plan.id(), patient,
                        DOCTOR, exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), date,
                        new IdempotencyKey("synthetic-availability-" + serial), "a".repeat(64), 1, 15, 10,
                        TherapySessionStatus.ABANDONED, 60, 10, entry == 0, start, null,
                        start.plusSeconds(120), "SYNTHETIC_ABANDONED"));
            }
        }
        if (n == 150) {
            plans.save(TherapyPlan.rehydrate(new TherapyPlanId(id(161, n)), patient, OTHER_DOCTOR,
                    TherapyPlanStatus.ACTIVE, 1, 3, 30, null, 1, 15, 10, Map.of(exercise.code(), 4)));
            plans.save(TherapyPlan.rehydrate(new TherapyPlanId(id(162, n)), patient, DOCTOR,
                    TherapyPlanStatus.SUSPENDED, 1, 3, 30, null, 1, 15, 10, Map.of(exercise.code(), 4)));
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

    @Test void doctorSystematicExplorationSummaryUsesFrozenSchemasAndAllowsClosedSessionReads() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (int n : List.of(45, 110, 111, 112))
            doctorSystematicSummarySuccess(doctorSystematicSummaryRequest(doctor, id(22, n).toString()), n);
    }

    @Test void doctorSystematicExplorationSummaryRequiresSessionAuthorshipAndItsOwnActiveAssignment() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        String other = token(OTHER_DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(other, id(22, 45).toString()), 403, "FORBIDDEN");
        // Session 114 belongs to OTHER_DOCTOR; its patient is assigned only to DOCTOR.
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 114).toString()), 403, "FORBIDDEN");
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(other, id(22, 114).toString()), 403, "FORBIDDEN");
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 113).toString()), 403, "FORBIDDEN");
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 1).toString()), 403, "FORBIDDEN");
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 40).toString()), 403, "FORBIDDEN");
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 46).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSystematicExplorationSummaryNativeAuthenticationAndRoleRejectionsAreSafe() throws Exception {
        String valid = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        for (String invalid : Arrays.asList(null, "not-a-jwt",
                valid.substring(0, valid.lastIndexOf('.') + 1) + "bad-signature",
                token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().minusSeconds(120)))) {
            var response = doctorSystematicSummaryRequest(invalid, id(22, 45).toString());
            assertEquals(401, response.statusCode());
            String challenge = response.header("WWW-Authenticate");
            assertTrue(challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("bearer"),
                    "Framework authentication rejection must advertise Bearer");
            safeBody(response, invalid);
        }
        // Framework rejections retain their own response body and cache-header contract.
        for (List<String> roles : List.of(List.of("PATIENT"), List.of("ADMIN"))) {
            String wrong = token(DOCTOR.value(), roles, Instant.now().plusSeconds(300));
            var response = doctorSystematicSummaryRequest(wrong, id(22, 45).toString());
            assertEquals(403, response.statusCode()); safeBody(response, wrong);
        }
        for (String extra : List.of("PATIENT", "ADMIN"))
            doctorSystematicSummaryError(doctorSystematicSummaryRequest(token(DOCTOR.value(), List.of("DOCTOR", extra), Instant.now().plusSeconds(300)),
                    id(22, 45).toString()), 403, "FORBIDDEN");
    }

    @Test void doctorSystematicExplorationSummaryCorruptFrozenPolicyIsUnavailableNotPermissionOrAbsence() throws Exception {
        String doctor = token(DOCTOR.value(), List.of("DOCTOR"), Instant.now().plusSeconds(300));
        // The existing malformed frozen JSON must fail in the shared reader, before granting any modality.
        doctorSystematicSummaryError(doctorSystematicSummaryRequest(doctor, id(22, 99).toString()), 503, "UNAVAILABLE");
    }

    private Response doctorSystematicSummaryRequest(String token, String session) {
        var request = given().baseUri("http://127.0.0.1").port(http.getPort()).redirects().follow(false);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = request.get("/api/internal/v1/therapy-sessions/" + session + "/systematic-exploration-summary-authorization");
        safeBody(response, token);
        assertNoEffects();
        return response;
    }

    private void doctorSystematicSummarySuccess(Response response, int n) throws Exception {
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.asString());
        Set<String> fields = new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        assertTrue(fields.equals(Set.of("contractVersion", "permitted", "therapySessionId", "configurationId",
                "configurationVersion", "minimumFixationMs", "maximumStimulusDurationMs")), "Unexpected DOCTOR authorization payload");
        assertTrue(body.path("contractVersion").isIntegralNumber() && body.path("contractVersion").asInt() == 1);
        assertTrue(body.path("permitted").isBoolean() && body.path("permitted").asBoolean());
        assertTrue(id(22, n).toString().equals(body.path("therapySessionId").asText()), "Wrong session binding");
        assertTrue(id(25, n).toString().equals(body.path("configurationId").asText()), "Wrong frozen configuration");
        assertTrue(body.path("configurationVersion").isIntegralNumber() && body.path("configurationVersion").asLong() == 1);
        assertTrue(body.path("minimumFixationMs").isIntegralNumber() && body.path("minimumFixationMs").asInt() == 500);
        assertTrue(body.path("maximumStimulusDurationMs").isIntegralNumber() && body.path("maximumStimulusDurationMs").asInt() == 3000);
        safeBody(response, null);
    }

    private void doctorSystematicSummaryError(Response response, int status, String suffix) throws Exception {
        assertEquals(status, response.statusCode()); assertEquals("no-store", response.header("Cache-Control"));
        JsonNode body = mapper.readTree(response.asString());
        assertTrue(body.isObject() && body.size() == 1
                && ("SYSTEMATIC_EXPLORATION_SUMMARY_AUTHORIZATION_" + suffix).equals(body.path("code").asText()),
                "Resource error must contain only a fixed DOCTOR authorization code");
        safeBody(response, null);
    }

    private void seedDoctorSystematicSummaryAuthorshipFixture() {
        int n = 114;
        var patient = new ClinicalPatientId(id(21, n));
        patients.save(ClinicalPatient.registerInvited(patient, new CognitoSubject(subject(n)), CREATED));
        assignments.save(DoctorPatientAssignment.assign(new DoctorPatientAssignmentId(id(23, n)), patient, DOCTOR, CREATED));
        var exercise = TherapeuticExercise.VISUAL_SCANNING;
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(id(24, n)), patient, OTHER_DOCTOR,
                TherapyPlanStatus.ACTIVE, 1, 10, 30, null, 1, 15, 10, Map.of(exercise.code(), 10));
        plans.save(plan);
        var parameters = new TherapeuticExerciseParameters.VisualScanning(
                TherapeuticExerciseParameters.VisualScanningMode.SYSTEMATIC_EXPLORATION,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
        var configuration = TherapeuticExerciseConfiguration.create(new TherapeuticConfigurationId(id(25, n)),
                plan.id(), exercise, 1, 1, CREATED, OTHER_DOCTOR, CREATED, parameters);
        configurations.append(configuration);
        sessions.save(TherapySession.rehydrate(new TherapySessionId(id(22, n)), plan.id(), patient, OTHER_DOCTOR,
                exercise.code(), TherapeuticConfigurationSnapshot.from(configuration), CREATED.atZone(ZoneOffset.UTC).toLocalDate(),
                new IdempotencyKey("synthetic-doctor-systematic-summary-authorship-114"), "a".repeat(64), 1, 15, 10,
                TherapySessionStatus.COMPLETED, 0, 0, true, CREATED, null, CREATED.plusSeconds(120), null));
    }

}
