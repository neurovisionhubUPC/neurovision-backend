package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.queryservices.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Detail;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.Session;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorSessionReportControllerTest {
    static final UUID PATIENT = new UUID(0, 1), SESSION = new UUID(0, 2), PLAN = new UUID(0, 3), CONFIG = new UUID(0, 4);
    static final String SUBJECT = "synthetic-doctor-subject", TOKEN = "synthetic-original-token";
    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final List<String> calls = new ArrayList<>();
    boolean author = true, assigned = true;
    String status = "COMPLETED", failAt;
    Identity doctorIdentity = new Identity(SUBJECT, Availability.AVAILABLE, "Synthetic", "Doctor", null, null);
    AssignedPatientNamesPort.Name patientName = new AssignedPatientNamesPort.Name("AVAILABLE", "Synthetic", "Patient");
    Observation observation = new ConfirmedNoEvidence(SESSION);

    void called(String stage) {
        calls.add(stage);
        if (stage.equals(failAt)) throw new IllegalStateException("private dependency " + SUBJECT + TOKEN);
    }

    ComposeSessionReportService composition() {
        var sessions = new DoctorTherapySessionQueryService(new DoctorTherapySessionReadRepository() {
            public DoctorTherapySessionReadRepository.Observation observe(UUID p, String d, Filters f, int o, int l) {
                throw new AssertionError("Report must not list sessions");
            }
            public Optional<Detail> observeDetail(UUID p, String d, UUID s) {
                called("detail"); assertEquals(PATIENT, p); assertEquals(SUBJECT, d); assertEquals(SESSION, s);
                var parameters = TherapeuticConfigurationTestData.fixationSnapshot().parameters();
                return author && assigned ? Optional.of(new Detail(PATIENT,
                        new Session(SESSION, PLAN, "fixation", status, START, "COMPLETED".equals(status) ? START.plusSeconds(120) : null,
                                120, LocalDate.of(2026, 10, 1), CONFIG, 2, 1), parameters,
                        new SessionLimits(1, 10, 25), TechnicalProgress.notApplicable())) : Optional.empty();
            }
        });
        AssignedPatientNamesPort names = (targets, token) -> {
            called("names"); assertEquals(Map.of(PATIENT, "synthetic-patient"), targets); assertEquals(TOKEN, token);
            return Map.of(PATIENT, patientName);
        };
        var patients = new AssignedPatientQueryService(new AssignedPatientReadPort() {
            public Page list(String d, int o, int l) { throw new AssertionError(); }
            public Optional<Patient> detail(String d, UUID p) { throw new AssertionError(); }
            public Map<UUID, String> resolve(String d, List<UUID> ids) {
                called("resolve"); assertEquals(SUBJECT, d); assertEquals(List.of(PATIENT), ids);
                return Map.of(PATIENT, "synthetic-patient");
            }
        }, names);
        return new ComposeSessionReportService(sessions, patients, names, (subject, token) -> {
            called("doctor"); assertEquals(SUBJECT, subject); assertEquals(TOKEN, token); return doctorIdentity;
        }, (session, token) -> {
            called("analytics"); assertEquals(SESSION, session); assertEquals(TOKEN, token); return observation;
        }, Clock.fixed(START.plusSeconds(180), ZoneOffset.UTC));
    }

    SecurityIdentity identity(boolean anonymous, Set<String> roles, String subject, String token, boolean jwt) {
        Object principal = jwt ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (p, m, a) -> m.getName().equals("getSubject") ? subject : null) : (java.security.Principal) () -> SUBJECT;
        return (SecurityIdentity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SecurityIdentity.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isAnonymous" -> anonymous;
                    case "hasRole" -> roles.contains(a[0]);
                    case "getPrincipal" -> principal;
                    case "getCredential" -> token == null || token.isBlank() ? null : new AccessTokenCredential(token);
                    default -> null;
                });
    }

    DoctorSessionReportController controller(SecurityIdentity identity) {
        return new DoctorSessionReportController(composition(), identity, mapper);
    }
    DoctorSessionReportController doctor() { return controller(identity(false, Set.of("DOCTOR"), SUBJECT, TOKEN, true)); }
    Response preview(DoctorSessionReportController controller) { return controller.preview(PATIENT.toString(), SESSION.toString()); }
    void error(Response response, int status, String code) {
        assertEquals(status, response.getStatus());
        assertEquals(Map.of("code", "SESSION_REPORT_" + code), response.getEntity());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals(status == 401 ? "Bearer" : null, response.getHeaderString("WWW-Authenticate"));
    }

    @Test void authenticatedPreviewEntryExists() {
        assertDoesNotThrow(() -> Class.forName(
                "com.neurovision.rehab.clinical.interfaces.rest.resource.DoctorSessionReportController"));
    }

    @Test void completedReportUsesAuthenticatedActorAndOriginalCredentialAfterClinicalGates() {
        var response = preview(doctor());
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertInstanceOf(SessionReportResponse.class, response.getEntity());
        JsonNode json = mapper.valueToTree(response.getEntity());
        assertEquals("Sin evidencia analítica disponible", json.path("analyticsNotice").asText());
        assertEquals("SUSTAINED_TARGET_FIXATION", json.path("mode").asText());
        assertEquals(List.of("detail", "resolve", "names", "doctor", "analytics"), calls);
        assertFalse(json.toString().contains(SUBJECT)); assertFalse(json.toString().contains(TOKEN));
        assertFalse(response.getEntity().toString().contains("Synthetic"));
    }

    @Test void realCompositionReturnsObservedEvidenceWithoutAbsenceNotice() {
        var source = new Source(SESSION, new UUID(0, 5), "baseline-v1", 7, 1, 1);
        var completeness = new Completeness(1, 1, 1, 0, false, List.of(), 0, true, null, new Counts(0, 0, 0));
        var grid = new Grid(10, 10, Collections.nCopies(100, 0L));
        var empty = new Numeric(0, null, null, null); var ordered = new Ordered(empty, null, null, null);
        var summary = new Summary(source, completeness, empty, Collections.nCopies(10, 0L), grid,
                new Coverage(null, 0, 0), new SessionReportAnalyticsPort.Path(0, empty, 0), new Gameplay(empty, ordered, ordered, ordered));
        var stream = new Stream(summary, new Heatmap(source, completeness, grid, "NORMALIZED_ZERO_TO_ONE", "RAW_GAZE_SAMPLE_COUNT", false),
                new GazePage(source, completeness, 100, 64, true, false, List.of()));
        observation = new ObservedEvidence(SESSION, 0, 20, false, List.of(stream));
        var response = preview(doctor()); assertEquals(200, response.getStatus());
        JsonNode json = mapper.valueToTree(response.getEntity());
        assertTrue(json.path("analyticsNotice").isNull());
        assertEquals("OBSERVED_EVIDENCE", json.path("analytics").path("availability").asText());
        assertEquals(7, json.path("analytics").path("streams").get(0).path("summary").path("source").path("revision").asLong());
        assertEquals(List.of("detail", "resolve", "names", "doctor", "analytics"), calls);
        assertFalse(json.toString().contains(SUBJECT)); assertFalse(json.toString().contains(TOKEN));
    }

    @Test void unauthenticatedOrMissingCredentialReturnsChallenged401BeforeAnyReads() {
        error(preview(controller(null)), 401, "UNAUTHENTICATED");
        error(preview(controller(identity(true, Set.of(), null, null, true))), 401, "UNAUTHENTICATED");
        for (String token : Arrays.asList(null, "", " "))
            error(preview(controller(identity(false, Set.of("DOCTOR"), SUBJECT, token, true))), 401, "UNAUTHENTICATED");
        assertTrue(calls.isEmpty());
    }

    @Test void wrongMixedRolesAndUntrustedPrincipalsReturn403WithoutReads() {
        for (var roles : List.of(Set.of("PATIENT"), Set.of("ADMIN"), Set.of("DOCTOR", "PATIENT"),
                Set.of("DOCTOR", "ADMIN"), Set.<String>of()))
            error(preview(controller(identity(false, roles, SUBJECT, TOKEN, true))), 403, "FORBIDDEN");
        error(preview(controller(identity(false, Set.of("DOCTOR"), SUBJECT, TOKEN, false))), 403, "FORBIDDEN");
        for (String subject : Arrays.asList(null, "", " ", " padded", "x".repeat(129)))
            error(preview(controller(identity(false, Set.of("DOCTOR"), subject, TOKEN, true))), 403, "FORBIDDEN");
        assertTrue(calls.isEmpty());
    }

    @Test void bothIdentifiersMustBeCanonicalBeforeReading() {
        for (String invalid : Arrays.asList(null, "bad", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000A")) {
            error(doctor().preview(invalid, SESSION.toString()), 400, "INVALID_REQUEST");
            error(doctor().preview(PATIENT.toString(), invalid), 400, "INVALID_REQUEST");
        }
        assertTrue(calls.isEmpty());
        error(controller(null).preview("bad", "bad"), 401, "UNAUTHENTICATED");
    }

    @Test void authorshipAndCurrentAssignmentDenialsNeverReadProfileOrAnalytics() {
        author = false; error(preview(doctor()), 403, "FORBIDDEN"); assertEquals(List.of("detail"), calls);
        calls.clear(); author = true; assigned = false;
        error(preview(doctor()), 403, "FORBIDDEN"); assertEquals(List.of("detail"), calls);
    }

    @Test void unfinishedOrFailedCompositionRemainsGeneric503RatherThanAbsence() {
        for (String state : List.of("ACTIVE", "CANCELLED", "ABANDONED", "FAILED_TECHNICAL")) {
            calls.clear(); status = state; error(preview(doctor()), 503, "UNAVAILABLE");
            assertEquals(List.of("detail"), calls);
        }
        status = "COMPLETED";
        var stages = List.of("detail", "resolve", "names", "doctor", "analytics");
        for (int i = 0; i < stages.size(); i++) {
            calls.clear(); failAt = stages.get(i); error(preview(doctor()), 503, "UNAVAILABLE");
            assertEquals(stages.subList(0, i + 1), calls);
        }
        failAt = null; observation = null; error(preview(doctor()), 503, "UNAVAILABLE");
    }

    @Test void missingAndPendingIdentitiesRemainSuccessfulAvailabilityNotAccessGates() {
        for (String availability : List.of("MISSING", "PENDING_COMPLETION")) {
            patientName = new AssignedPatientNamesPort.Name(availability, null, null);
            doctorIdentity = new Identity(SUBJECT, Availability.valueOf(availability), null, null, null, null);
            var response = preview(doctor()); assertEquals(200, response.getStatus());
            JsonNode json = mapper.valueToTree(response.getEntity());
            assertEquals(availability, json.path("doctor").path("availability").asText());
            assertEquals(availability, json.path("patient").path("availability").asText());
            assertTrue(json.path("patient").path("firstName").isNull());
        }
    }

    @Test void projectionFailureIsUnavailableNotAnInvalidCallerRequest() {
        var failingMapper = new ObjectMapper() {
            @Override public <T extends JsonNode> T valueToTree(Object value) {
                throw new IllegalArgumentException("private serialization details");
            }
        };
        error(preview(new DoctorSessionReportController(composition(),
                identity(false, Set.of("DOCTOR"), SUBJECT, TOKEN, true), failingMapper)), 503, "UNAVAILABLE");
        assertEquals(List.of("detail", "resolve", "names", "doctor", "analytics"), calls);
    }

    @Test void routeIsReadOnlyDoctorOnlyAndAcceptsOnlyTwoPathIdentifiers() throws Exception {
        var type = DoctorSessionReportController.class;
        assertEquals("/api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-preview",
                type.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertNotNull(type.getAnnotation(jakarta.enterprise.context.RequestScoped.class));
        var method = type.getMethod("preview", String.class, String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
        assertArrayEquals(new String[]{"DOCTOR"}, method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
        assertEquals("clinicalPatientId", method.getParameters()[0].getAnnotation(jakarta.ws.rs.PathParam.class).value());
        assertEquals("therapySessionId", method.getParameters()[1].getAnnotation(jakarta.ws.rs.PathParam.class).value());
        assertNotNull(SessionReportResponse.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class));
    }
}
