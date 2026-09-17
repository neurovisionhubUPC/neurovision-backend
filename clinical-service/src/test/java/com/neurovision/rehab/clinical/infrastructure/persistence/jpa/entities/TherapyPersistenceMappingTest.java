package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.domain.model.aggregates.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TherapyPersistenceMappingTest {
    private final TherapeuticConfigurationJsonCodec codec =
            new TherapeuticConfigurationJsonCodec(new ObjectMapper());

    @Test
    void shouldRoundTripPlanAndSessionWithoutLosingFrozenEvidence() {
        var plan = TherapyPlan.rehydrate(new TherapyPlanId(UUID.randomUUID()),
                new ClinicalPatientId(UUID.randomUUID()), new CognitoSubject("doctor-subject"),
                TherapyPlanStatus.ACTIVE, 4, 3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
        var restoredPlan = TherapyPlanEntity.fromDomain(plan).toDomain();
        assertEquals(plan.configurationVersion(), restoredPlan.configurationVersion());
        assertEquals(plan.dailyExercisePrescriptions(), restoredPlan.dailyExercisePrescriptions());

        var start = Instant.parse("2026-09-13T15:00:00Z");
        var session = TherapySession.start(new TherapySessionId(UUID.randomUUID()), plan, new ExerciseCode("visual-search"),
                LocalDate.of(2026, 9, 13), visualSearchSnapshot(),
                new IdempotencyKey("attempt-1"), "a".repeat(64), start, start.plusSeconds(120));
        session.recordAcceptedActivity(Duration.ofMinutes(5), 50, start.plusSeconds(60), start.plusSeconds(180));
        session.cancel("Safety interruption", start.plusSeconds(61));

        var restored = TherapySessionEntity.fromDomain(session, codec).toDomain(codec);
        assertEquals(TherapySessionStatus.CANCELLED, restored.status());
        assertEquals(300, restored.activeDurationSeconds());
        assertEquals(50, restored.validSampleCount());
        assertEquals("Safety interruption", restored.terminationReason().orElseThrow());
        assertEquals(session.requestFingerprint(), restored.requestFingerprint());
        assertEquals(session.therapeuticConfigurationId(), restored.therapeuticConfigurationId());
        assertEquals(session.configurationParameters(), restored.configurationParameters());
    }
}
