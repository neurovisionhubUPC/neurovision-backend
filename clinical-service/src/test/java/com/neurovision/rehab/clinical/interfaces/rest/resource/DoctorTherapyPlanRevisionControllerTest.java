package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTherapyPlanRevisionControllerTest {
    private final UUID patient = new UUID(0, 1), planId = new UUID(0, 2);
    private final CognitoSubject doctor = new CognitoSubject("synthetic-doctor");
    private final Instant now = Instant.parse("2026-10-22T16:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<TherapyPlanRevision> stored = new ArrayList<>();
    private TherapyPlan plan;
    private boolean assigned;
    private String eligibility;
    private int profileCalls, planLocks;
    private DoctorTherapyPlanRevisionCommandService application;

    @BeforeEach void setup() {
        stored.clear(); assigned = true; eligibility = "allow"; profileCalls = 0; planLocks = 0;
        plan = TherapyPlan.rehydrate(new TherapyPlanId(planId), new ClinicalPatientId(patient), doctor,
                TherapyPlanStatus.ACTIVE, 4, 3, 30, 60, 5, 15, 10, Map.of(new ExerciseCode("fixation"), 1));
        var plans = proxy(TherapyPlanRepository.class, (method, args) -> {
            if (method.equals("findByIdForUpdate")) { planLocks++; return Optional.ofNullable(plan); }
            throw new AssertionError("Unexpected plan operation");
        });
        var assignments = proxy(DoctorPatientAssignmentRepository.class, (method, args) -> {
            if (method.equals("findActiveByClinicalPatientIdAndDoctorSubjectForUpdate")) {
                return assigned ? Optional.of(DoctorPatientAssignment.assign(
                        new DoctorPatientAssignmentId(new UUID(0, 3)), new ClinicalPatientId(patient), doctor, now)) : Optional.empty();
            }
            throw new AssertionError("Unexpected assignment operation");
        });
        var revisions = proxy(TherapyPlanRevisionRepository.class, (method, args) -> switch (method) {
            case "findByCommandId" -> stored.stream().filter(r -> r.commandId().equals(args[1])).toList();
            case "findEffective" -> stored.stream().filter(r -> !r.effectiveFrom().isAfter((Instant) args[1])).toList();
            case "findLatestSequence" -> (long) stored.size();
            case "appendAll" -> { stored.addAll((List<TherapyPlanRevision>) args[0]); yield null; }
            default -> throw new AssertionError("Unexpected revision operation");
        });
        var sessions = proxy(TherapySessionRepository.class, (method, args) -> {
            if (method.equals("findActiveByPlanForUpdate")) return Optional.empty();
            throw new AssertionError("Unexpected session write");
        });
        var delegate = new ReviseTherapyPlanCommandService(plans, revisions, assignments, sessions,
                UUID::randomUUID, Clock.fixed(now, ZoneOffset.UTC));
        application = new DoctorTherapyPlanRevisionCommandService(delegate::handle, subject -> {
            assertEquals(doctor, subject); profileCalls++;
            if (eligibility.equals("deny")) throw new SecurityException("private-profile-detail");
            if (eligibility.equals("unavailable")) throw new DoctorSchedulingUnavailableException();
        });
    }

    @FunctionalInterface interface Operation { Object apply(String method, Object[] arguments); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Operation operation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, method, args) -> operation.apply(method.getName(), args));
    }
    private SecurityIdentity identity(boolean anonymous, boolean credential, Set<String> roles) {
        var jwt = proxy(JsonWebToken.class, (method, args) -> method.equals("getSubject") ? doctor.value() : null);
        return proxy(SecurityIdentity.class, (method, args) -> switch (method) {
            case "isAnonymous" -> anonymous;
            case "hasRole" -> roles.contains(args[0]);
            case "getPrincipal" -> jwt;
            case "getCredential" -> credential ? new AccessTokenCredential("synthetic-token") : null;
            default -> null;
        });
    }
    private DoctorTherapyPlanRevisionController controller(SecurityIdentity identity) {
        return new DoctorTherapyPlanRevisionController(application, identity, mapper);
    }
    private Response request(String changes) { return request(UUID.randomUUID(), changes); }
    private Response request(UUID command, String changes) {
        return controller(identity(false, true, Set.of("DOCTOR"))).revise(patient.toString(), planId.toString(),
                "{\"commandId\":\"" + command + "\"," + changes + "}");
    }
    private JsonNode json(Response response) { return mapper.valueToTree(response.getEntity()); }
    private void error(Response response, int status, String code) {
        assertEquals(status, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals(mapper.valueToTree(Map.of("code", "PLAN_REVISION_" + code)), json(response));
    }

    @Test void declaresTheRevisionEndpointForDoctorsOnly() throws Exception {
        var type = Class.forName("com.neurovision.rehab.clinical.interfaces.rest.resource.DoctorTherapyPlanRevisionController");
        assertEquals("/api/v1/clinical-patients/{clinicalPatientId}/therapy-plans/{therapyPlanId}/revisions",
                type.getAnnotation(jakarta.ws.rs.Path.class).value());
        var method = type.getMethod("revise", String.class, String.class, String.class);
        assertNotNull(method.getAnnotation(jakarta.ws.rs.POST.class));
        assertArrayEquals(new String[]{"DOCTOR"}, method.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value());
    }

    @Test void schedulingChangesRequireEligibilityIncludingEveryMixedStopField() {
        for (String field : List.of("\"dailySessionLimit\":2", "\"dailyTargetDurationMinutes\":20",
                "\"dailyHardSafetyLimitMinutes\":45", "\"dailyExercisePrescriptions\":{\"fixation\":2}")) {
            for (String prefix : List.of("", "\"status\":\"SUSPENDED\",\"clinicalReason\":\"Synthetic reason\",",
                    "\"status\":\"CANCELLED\",\"clinicalReason\":\"Synthetic reason\",")) {
                eligibility = "deny"; error(request(prefix + field), 403, "FORBIDDEN");
                eligibility = "unavailable"; error(request(prefix + field), 503, "UNAVAILABLE");
                assertEquals(0, planLocks); assertTrue(stored.isEmpty());
            }
        }
        assertEquals(24, profileCalls);
    }

    @Test void stopOnlyRequestsNeverConsultProfileButStillRequireOwnershipAndAssignment() {
        eligibility = "unavailable";
        for (String status : List.of("SUSPENDED", "CANCELLED")) {
            stored.clear();
            var response = request("\"status\":\"" + status + "\",\"clinicalReason\":\"Synthetic reason\"");
            assertEquals(201, response.getStatus()); assertEquals(1, stored.size());
        }
        assertEquals(0, profileCalls);
        stored.clear(); assigned = false;
        error(request("\"status\":\"SUSPENDED\",\"clinicalReason\":\"Synthetic reason\""), 403, "FORBIDDEN");
        assertTrue(stored.isEmpty());
        assigned = true;
        for (String mode : List.of("unknown", "wrongPatient", "wrongOwner")) {
            plan = mode.equals("unknown") ? null : TherapyPlan.rehydrate(new TherapyPlanId(planId),
                    new ClinicalPatientId(mode.equals("wrongPatient") ? new UUID(0, 99) : patient),
                    mode.equals("wrongOwner") ? new CognitoSubject("other-doctor") : doctor,
                    TherapyPlanStatus.ACTIVE, 4, 3, 30, 60, 5, 15, 10, Map.of(new ExerciseCode("fixation"), 1));
            error(request("\"status\":\"CANCELLED\",\"clinicalReason\":\"Synthetic reason\""), 403, "FORBIDDEN");
            assertTrue(stored.isEmpty());
        }
        assertEquals(0, profileCalls);
    }

    @Test void schedulingReplayIsIdempotentAndRechecksCurrentEligibilityAndAssignment() {
        var command = UUID.randomUUID(); var first = request(command, "\"dailySessionLimit\":2");
        assertEquals(201, first.getStatus()); assertEquals("no-store", first.getHeaderString("Cache-Control"));
        var replay = request(command, "\"dailySessionLimit\":2");
        assertEquals(200, replay.getStatus()); assertTrue(json(replay).get("idempotentReplay").booleanValue());
        assertEquals(json(first).get("revisions"), json(replay).get("revisions")); assertEquals(1, stored.size());
        error(request(command, "\"dailySessionLimit\":1"), 409, "CONFLICT"); assertEquals(1, stored.size());
        eligibility = "deny"; error(request(command, "\"dailySessionLimit\":2"), 403, "FORBIDDEN");
        eligibility = "allow"; assigned = false;
        error(request(command, "\"dailySessionLimit\":2"), 403, "FORBIDDEN"); assertEquals(1, stored.size());
    }

    @Test void cancellationIsTerminalButItsAuthorizedIdenticalReplayStillSucceeds() {
        var command = UUID.randomUUID(); String stop = "\"status\":\"CANCELLED\",\"clinicalReason\":\"Synthetic reason\"";
        assertEquals(201, request(command, stop).getStatus());
        assertEquals(200, request(command, stop).getStatus()); assertEquals(1, stored.size());
        error(request("\"dailySessionLimit\":2"), 409, "CONFLICT"); assertEquals(1, stored.size());
    }

    @Test void mixedEffectsRetainExistingTimingAndSafeResponseWhitelist() {
        var result = request("\"status\":\"SUSPENDED\",\"clinicalReason\":\" Synthetic reason \",\"dailyTargetDurationMinutes\":40");
        assertEquals(201, result.getStatus()); assertEquals(1, profileCalls); assertEquals(2, stored.size());
        assertEquals(now, stored.get(0).effectiveFrom());
        assertEquals(Instant.parse("2026-10-23T05:00:00Z"), stored.get(1).effectiveFrom());
        var response = json(result); var fields = new HashSet<String>(); response.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("commandId", "therapyPlanId", "idempotentReplay", "revisions"), fields);
        String encoded = response.toString();
        for (String privateField : List.of("commandFingerprint", "createdByDoctorSubject", "doctorSubject", "synthetic-doctor", "synthetic-token")) {
            assertFalse(encoded.contains(privateField));
        }
        assertEquals("SUSPENDED", response.at("/revisions/0/changes/status").textValue());
        assertEquals("Synthetic reason", response.at("/revisions/0/changes/clinicalReason").textValue());
        assertFalse(response.at("/revisions/1/changes").has("status"));
    }

    @Test void invalidTransportCannotReachProfileOrPersistenceOrResumeAnActivePlan() {
        for (String changes : List.of("\"status\":\"ACTIVE\"", "\"status\":\"SUSPENDED\"",
                "\"dailySessionLimit\":null", "\"dailySessionLimit\":0", "\"dailySessionLimit\":1.5",
                "\"clinicalReason\":\"Only a reason\"", "\"dailySessionLimit\":2,\"extra\":1")) {
            error(request(changes), 400, "INVALID_REQUEST");
        }
        assertEquals(0, profileCalls); assertEquals(0, planLocks); assertTrue(stored.isEmpty());
    }

    @Test void anonymousMissingCredentialAndMixedRolesAreDeniedBeforeParsingOrCallingDependencies() {
        error(controller(identity(true, false, Set.of())).revise("bad", "bad", "bad"), 401, "UNAUTHENTICATED");
        error(controller(identity(false, false, Set.of("DOCTOR"))).revise("bad", "bad", "bad"), 401, "UNAUTHENTICATED");
        for (var roles : List.of(Set.of("PATIENT"), Set.of("DOCTOR", "PATIENT"), Set.of("DOCTOR", "ADMIN"))) {
            error(controller(identity(false, true, roles)).revise("bad", "bad", "bad"), 403, "FORBIDDEN");
        }
        assertEquals(0, profileCalls); assertEquals(0, planLocks); assertTrue(stored.isEmpty());
    }

    @Test void unexpectedFailuresAreUnavailableAndNativeRecordsAreExplicitlyRegistered() {
        var failing = new DoctorTherapyPlanRevisionCommandService(command -> { throw new RuntimeException("private-database-detail"); }, subject -> {});
        var controller = new DoctorTherapyPlanRevisionController(failing, identity(false, true, Set.of("DOCTOR")), mapper);
        error(controller.revise(patient.toString(), planId.toString(), "{\"commandId\":\"" + UUID.randomUUID()
                + "\",\"status\":\"CANCELLED\",\"clinicalReason\":\"Synthetic reason\"}"), 503, "UNAVAILABLE");
        var annotation = TherapyPlanRevisionResponse.class.getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class);
        assertEquals(Set.of(TherapyPlanRevisionResponse.Result.class, TherapyPlanRevisionResponse.Revision.class,
                TherapyPlanRevisionResponse.Changes.class), Set.of(annotation.targets()));
    }

    @Test void rejectedCommandsAndReplayPreservePreviouslyStoredRevisions() {
        var command = UUID.randomUUID();
        var first = request(command, "\"dailySessionLimit\":2");
        assertEquals(201, first.getStatus());
        var before = List.copyOf(stored);
        var beforeResponse = json(first).get("revisions").deepCopy();
        error(request("\"dailyExercisePrescriptions\":{\"fixation\":1,\"FIXATION\":2}"), 400, "INVALID_REQUEST");
        assertEquals(before, stored);
        error(request(command, "\"dailySessionLimit\":1"), 409, "CONFLICT"); assertEquals(before, stored);
        assigned = false;
        error(request("\"status\":\"CANCELLED\",\"clinicalReason\":\"Synthetic reason\""), 403, "FORBIDDEN");
        assertEquals(before, stored);
        assigned = true; eligibility = "unavailable";
        error(request("\"dailySessionLimit\":1"), 503, "UNAVAILABLE"); assertEquals(before, stored);
        eligibility = "allow";
        var replay = request(command, "\"dailySessionLimit\":2");
        assertEquals(200, replay.getStatus()); assertEquals(before, stored);
        assertEquals(beforeResponse, json(replay).get("revisions"));
    }
}
