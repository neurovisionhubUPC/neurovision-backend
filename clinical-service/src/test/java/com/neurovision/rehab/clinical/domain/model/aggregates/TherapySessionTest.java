package com.neurovision.rehab.clinical.domain.model.aggregates;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TherapySessionTest {
    private static final Instant START = Instant.parse("2026-09-13T15:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void shouldStartDirectlyActiveWithFrozenPlanAndTherapeuticConfiguration() {
        var session = session();

        assertEquals(TherapySessionStatus.ACTIVE, session.status());
        assertEquals(1, session.configurationVersion());
        assertEquals(TherapeuticExercise.VISUAL_SEARCH, session.therapeuticExercise());
        assertEquals(visualSearchSnapshot().configurationId(), session.therapeuticConfigurationId());
        assertEquals(visualSearchSnapshot().parameters(), session.configurationParameters());
        assertEquals(5, session.minimumValidDurationMinutes());
        assertEquals(LocalDate.of(2026, 9, 13), session.quotaDate());
        assertTrue(session.consumesDailyQuota());
    }

    @Test
    void shouldRequireDurationAndValidSamplesForMeaningfulActivity() {
        var session = session();
        session.recordAcceptedActivity(Duration.ofMinutes(5), 49, START.plusSeconds(60), START.plusSeconds(180));
        assertFalse(session.meaningfulActivityReached());

        session.recordAcceptedActivity(Duration.ZERO, 1, START.plusSeconds(61), START.plusSeconds(181));
        assertTrue(session.meaningfulActivityReached());
    }

    @Test
    void shouldReleaseReservedSlotWhenTechnicalFailureOccursBeforeMeaningfulActivity() {
        var session = session();
        session.recordAcceptedActivity(Duration.ofMinutes(2), 20, START.plusSeconds(60), START.plusSeconds(180));
        session.failTechnical("CAMERA_UNAVAILABLE", START.plusSeconds(61));

        assertEquals(TherapySessionStatus.FAILED_TECHNICAL, session.status());
        assertFalse(session.consumesDailyQuota());
        assertEquals(120, session.activeDurationSeconds());
    }

    @Test
    void shouldConsumeReservedSlotWhenCancelledAfterMeaningfulActivity() {
        var session = session();
        session.recordAcceptedActivity(Duration.ofMinutes(5), 50, START.plusSeconds(60), START.plusSeconds(180));
        session.cancel("Patient reported dizziness", START.plusSeconds(61));

        assertEquals(TherapySessionStatus.CANCELLED, session.status());
        assertTrue(session.consumesDailyQuota());
    }

    @Test
    void shouldExpireLeaseAsTechnicalFailureAndKeepTerminalStateImmutable() {
        var session = session();
        session.expireLease(START.plusSeconds(120));

        assertEquals(TherapySessionStatus.FAILED_TECHNICAL, session.status());
        assertEquals("SESSION_LEASE_EXPIRED", session.terminationReason().orElseThrow());
        assertThrows(IllegalStateException.class, () -> session.complete(START.plusSeconds(121)));
    }

    @Test
    void shouldRejectMissingCancellationReasonAndPrematureLeaseExpiry() {
        var session = session();
        assertThrows(IllegalArgumentException.class, () -> session.cancel(" ", START.plusSeconds(10)));
        assertThrows(IllegalStateException.class, () -> session.expireLease(START.plusSeconds(119)));
    }

    @Test
    void shouldAllowExactMaximumDurationAndRejectOverflowWithoutMutation() {
        var session = session();
        session.recordAcceptedActivity(Duration.ofMinutes(15), 100, START.plusSeconds(60), START.plusSeconds(180));
        assertEquals(15 * 60, session.activeDurationSeconds());
        assertEquals(100, session.validSampleCount());

        assertThrows(IllegalStateException.class, () -> session.recordAcceptedActivity(
                Duration.ofSeconds(1), 1, START.plusSeconds(61), START.plusSeconds(181)));
        assertEquals(15 * 60, session.activeDurationSeconds());
        assertEquals(100, session.validSampleCount());
        assertEquals(START.plusSeconds(180), session.leaseExpiresAt().orElseThrow());
    }

    @Test
    void shouldEnforceTerminationReasonBoundary() {
        var session = session();
        session.cancel("x".repeat(500), START.plusSeconds(1));
        assertEquals(500, session.terminationReason().orElseThrow().length());

        var another = session();
        assertThrows(IllegalArgumentException.class,
                () -> another.cancel("x".repeat(501), START.plusSeconds(1)));
    }

    @Test
    void shouldRequireDatabaseSafeRequestFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> TherapySession.start(
                new TherapySessionId(UUID.randomUUID()), plan(), new ExerciseCode("visual-search"),
                LocalDate.of(2026, 9, 13), visualSearchSnapshot(), new IdempotencyKey("attempt-invalid"),
                "a".repeat(63), START, START.plusSeconds(120)));
        assertThrows(IllegalArgumentException.class, () -> TherapySession.start(
                new TherapySessionId(UUID.randomUUID()), plan(), new ExerciseCode("visual-search"),
                LocalDate.of(2026, 9, 13), visualSearchSnapshot(), new IdempotencyKey("attempt-uppercase"),
                "A".repeat(64), START, START.plusSeconds(120)));
    }

    private static TherapySession session() {
        return TherapySession.start(new TherapySessionId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                plan(), new ExerciseCode("visual-search"), LocalDate.of(2026, 9, 13), visualSearchSnapshot(),
                new IdempotencyKey("attempt-1"),
                FINGERPRINT, START, START.plusSeconds(120));
    }

    static TherapyPlan plan() {
        return TherapyPlan.rehydrate(new TherapyPlanId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                new ClinicalPatientId(UUID.fromString("30000000-0000-0000-0000-000000000001")),
                new CognitoSubject("doctor-subject"), TherapyPlanStatus.ACTIVE, 7,
                3, 30, 60, 5, 15, 50,
                Map.of(new ExerciseCode("visual-search"), 1, new ExerciseCode("fixation"), 2));
    }
}
