package com.neurovision.rehab.clinical.domain.model.aggregates;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TherapyPlanAdmissionTest {
    TherapyPlan plan(TherapyPlanStatus status, Integer safety) {
        return TherapyPlan.rehydrate(new TherapyPlanId(new UUID(0, 1)), new ClinicalPatientId(new UUID(0, 2)),
                new CognitoSubject("synthetic-doctor"), status, 1, 3, 30, safety, 5, 15, 50,
                Map.of(new ExerciseCode("fixation"), 2));
    }
    @Test void typedDecisionsPreserveExistingDenialPriorityAndMessages() {
        String[][] cases = {
            {"SUSPENDED", "3", "2", "9999", "PLAN_NOT_ACTIVE", "Therapy plan is not active"},
            {"ACTIVE", "3", "2", "9999", "PLAN_QUOTA_EXHAUSTED", "Daily session quota is exhausted"},
            {"ACTIVE", "0", "2", "9999", "EXERCISE_QUOTA_EXHAUSTED", "Daily prescribed quota for exercise is exhausted"},
            {"ACTIVE", "0", "0", "1501", "DAILY_TARGET_TIME_INSUFFICIENT", "Remaining daily target cannot accommodate a valid session"}
        };
        for (var c : cases) {
            var p = plan(TherapyPlanStatus.valueOf(c[0]), 60); var code = new ExerciseCode("fixation");
            assertEquals(c[4], p.admissionBlocker(code, Long.parseLong(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3])).orElseThrow().name());
            assertEquals(c[5], assertThrows(IllegalStateException.class, () -> p.validateAdmission(code,
                    Long.parseLong(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3]))).getMessage());
        }
        var p = plan(TherapyPlanStatus.ACTIVE, 20);
        assertEquals("DAILY_SAFETY_TIME_INSUFFICIENT", p.admissionBlocker(new ExerciseCode("fixation"), 0, 0, 301).orElseThrow().name());
        assertEquals("Remaining hard safety allowance cannot accommodate the session", assertThrows(IllegalStateException.class,
                () -> p.validateAdmission(new ExerciseCode("fixation"), 0, 0, 301)).getMessage());
    }
    @Test void existingExactTargetAndSafetyBoundariesAndOptionalSafetyStillAdmit() {
        var code = new ExerciseCode("fixation");
        for (var p : List.of(plan(TherapyPlanStatus.ACTIVE, 20), plan(TherapyPlanStatus.ACTIVE, null))) {
            assertTrue(p.admissionBlocker(code, 2, 1, 300).isEmpty());
            assertDoesNotThrow(() -> p.validateAdmission(code, 2, 1, 300));
            assertEquals(3, p.dailySessionLimit()); assertEquals(TherapyPlanStatus.ACTIVE, p.status());
        }
        assertTrue(plan(TherapyPlanStatus.ACTIVE, null).admissionBlocker(code, 0, 0, 1500).isEmpty());
    }
}
