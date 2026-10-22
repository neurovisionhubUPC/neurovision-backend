package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.domain.model.valueobjects.CognitoSubject;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TherapyPlanRevisionRestAssemblerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String patient = new UUID(0, 1).toString(), plan = new UUID(0, 2).toString();
    private final String command = "01234567-89ab-cdef-0123-456789abcdef";
    private void parse(String body) {
        TherapyPlanRevisionRestAssembler.toCommand(patient, plan, new CognitoSubject("doctor"), body, mapper);
    }
    private String body(String fields) { return "{\"commandId\":\"" + command + "\"," + fields + "}"; }

    @Test void rejectsInvalidShapesUnknownDuplicateAndTrailingFields() {
        for (String value : Arrays.asList(null, "", "null", "[]", "{}", "true", "{", body("\"dailySessionLimit\":2") + "{}",
                body("\"dailySessionLimit\":2,\"dailySessionLimit\":3"), body("\"status\":\"SUSPENDED\",\"status\":\"CANCELLED\",\"clinicalReason\":\"reason\""),
                body("\"doctorSubject\":\"other\",\"dailySessionLimit\":2"), body("\"effectiveFrom\":\"2099-01-01T00:00:00Z\",\"dailySessionLimit\":2"),
                body("\"configurationVersion\":4,\"dailySessionLimit\":2"), " ".repeat(16385))) {
            assertThrows(IllegalArgumentException.class, () -> parse(value));
        }
    }

    @Test void everyOptionalExplicitNullAndNonIntegerOrNonPositiveLimitIsRejected() {
        for (String field : List.of("dailySessionLimit", "dailyTargetDurationMinutes", "dailyHardSafetyLimitMinutes",
                "dailyExercisePrescriptions", "status", "clinicalReason")) {
            assertThrows(IllegalArgumentException.class, () -> parse(body("\"" + field + "\":null")));
        }
        for (String field : List.of("dailySessionLimit", "dailyTargetDurationMinutes", "dailyHardSafetyLimitMinutes")) {
            for (String invalid : List.of("0", "-1", "1.0", "true", "\"2\"", "2147483648", "{}")) {
                assertThrows(IllegalArgumentException.class, () -> parse(body("\"" + field + "\":" + invalid)));
            }
        }
    }

    @Test void statusesReasonsAndExerciseDistributionAreStrict() {
        for (String fields : List.of("\"status\":\"ACTIVE\"", "\"status\":\"suspended\"", "\"status\":2",
                "\"status\":\"SUSPENDED\"", "\"status\":\"CANCELLED\",\"clinicalReason\":\" \"",
                "\"status\":\"CANCELLED\",\"clinicalReason\":\"" + "x".repeat(501) + "\"",
                "\"dailyExercisePrescriptions\":{}", "\"dailyExercisePrescriptions\":[]",
                "\"dailyExercisePrescriptions\":{\"unknown\":1}", "\"dailyExercisePrescriptions\":{\"fixation\":0}",
                "\"dailyExercisePrescriptions\":{\"fixation\":null}", "\"dailyExercisePrescriptions\":{\"fixation\":1,\"fixation\":2}")) {
            assertThrows(IllegalArgumentException.class, () -> parse(body(fields)));
        }
        assertDoesNotThrow(() -> parse(body("\"status\":\"CANCELLED\",\"clinicalReason\":\"" + "x".repeat(500) + "\"")));
    }

    @Test void canonicalIdsOmittedValuesAndExistingExerciseCodesArePreserved() {
        var value = TherapyPlanRevisionRestAssembler.toCommand(patient, plan, new CognitoSubject("doctor"),
                body("\"dailyExercisePrescriptions\":{\"fixation\":1,\"visual-scanning\":2,\"visual-search\":3}"), mapper);
        assertNull(value.dailySessionLimit()); assertNull(value.status()); assertNull(value.clinicalReason());
        assertEquals(3, value.dailyExercisePrescriptions().size());
        for (String invalid : List.of(command.toUpperCase(Locale.ROOT), " " + command, "not-a-uuid")) {
            assertThrows(IllegalArgumentException.class, () -> parse(body("\"dailySessionLimit\":2").replace(command, invalid)));
            assertThrows(IllegalArgumentException.class, () -> TherapyPlanRevisionRestAssembler.toCommand(
                    invalid, plan, new CognitoSubject("doctor"), body("\"dailySessionLimit\":2"), mapper));
            assertThrows(IllegalArgumentException.class, () -> TherapyPlanRevisionRestAssembler.toCommand(
                    patient, invalid, new CognitoSubject("doctor"), body("\"dailySessionLimit\":2"), mapper));
        }
    }

    @Test void domainNormalizationIsPreservedButCanonicalDuplicateExercisesAreRejected() {
        var normalized = TherapyPlanRevisionRestAssembler.toCommand(patient, plan, new CognitoSubject("doctor"),
                body("\"dailyExercisePrescriptions\":{\" FIXATION \":2}"), mapper);
        assertEquals(2, normalized.dailyExercisePrescriptions().get(
                new com.neurovision.rehab.clinical.domain.model.valueobjects.ExerciseCode("fixation")));
        for (String alias : List.of("FIXATION", " fixation ")) {
            assertThrows(IllegalArgumentException.class, () -> parse(body(
                    "\"dailyExercisePrescriptions\":{\"fixation\":1,\"" + alias + "\":2}")));
        }
    }
}
