package com.neurovision.rehab.clinical.interfaces.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.clinical.application.internal.queryservices.ListPatientTherapyPlansQueryServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.internal.queryservices.PatientTherapyPlanView;
import com.neurovision.rehab.clinical.application.ports.in.*;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import jakarta.ws.rs.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.queryservices.ListPatientTherapyPlansQueryServiceTest.readOnly;

class PatientTherapyControllerOfflineTest {
    final Fixture f = new Fixture(); boolean patient, doctor; int queries;
    RuntimeException failure; List<PatientTherapyPlanView> views;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final JsonWebToken token = readOnly(JsonWebToken.class, (name, args) -> {
        if (name.equals("getSubject")) return f.subject.value(); throw new AssertionError("Unexpected token operation");
    });
    final SecurityIdentity identity = readOnly(SecurityIdentity.class, (name, args) -> switch (name) {
        case "getPrincipal" -> token;
        case "hasRole" -> args[0].equals("PATIENT") ? patient : args[0].equals("DOCTOR") && doctor;
        default -> throw new AssertionError("Unexpected identity operation");
    });
    PatientTherapyController controller() {
        PatientTherapyPlanQueryUseCase plans = subject -> { queries++; if (failure != null) throw failure; return views; };
        PatientTherapySessionStartUseCase starter = (subject, plan, exercise, key) -> { throw new AssertionError("Unexpected session start"); };
        return new PatientTherapyController(plans, starter, identity, mapper);
    }
    @Test void existingListFieldsRemainAlongsideAvailabilityAndNullableExpiredBalances() {
        patient = true;
        for (boolean expired : List.of(false, true)) {
            if (expired) f.active(Fixture.NOW); views = f.service().handle(f.subject);
            var response = mapper.valueToTree(controller().list()).get(0);
            assertEquals(f.planId.value().toString(), response.path("therapyPlanId").asText());
            assertEquals(3, response.path("dailySessionLimit").asInt());
            assertEquals(2, response.path("dailyExercisePrescriptions").path("fixation").asInt());
            assertEquals(1, response.path("effectiveConfigurations").size());
            var a = response.path("dailyAvailability"); assertEquals("2026-09-13", a.path("quotaDate").asText());
            assertEquals("2026-09-14T04:30:00Z", a.path("evaluatedAt").asText());
            assertEquals(expired ? "CHECK_AT_START" : "AVAILABLE", a.path("exercises").get(0).path("status").asText());
            assertEquals(expired, a.path("remainingPlanSessionSlots").isNull());
            assertEquals(expired, a.path("exercises").get(0).path("remainingPrescribedSlots").isNull());
        }
    }
    @Test void identityAndQueryDenialsRemainForbiddenBeforeAvailabilityReads() {
        assertThrows(ForbiddenException.class, () -> controller().list()); assertEquals(0, queries);
        patient = true; doctor = true; assertThrows(ForbiddenException.class, () -> controller().list()); assertEquals(0, queries);
        doctor = false; failure = new SecurityException("synthetic private detail");
        assertThrows(ForbiddenException.class, () -> controller().list());
    }
    @Test void readFailureIsSanitized503AndNotEmptySuccess() {
        patient = true; failure = new IllegalStateException("synthetic private detail");
        var error = assertThrows(WebApplicationException.class, () -> controller().list());
        assertEquals(503, error.getResponse().getStatus()); assertNull(error.getCause());
        assertEquals(Map.of("code", "PATIENT_THERAPY_AVAILABILITY_UNAVAILABLE"), error.getResponse().getEntity());
        assertFalse(error.getMessage().contains("synthetic private detail"));
    }
    @Test void authorizedNoPlansRemainAnEmptyList() { patient = true; views = List.of(); assertTrue(controller().list().isEmpty()); }
}
