package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompensatorySaccadeTrialMetricsCalculatorTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final UUID CONFIGURATION = new UUID(0, 2);
    private static final DataOrigin ORIGIN = DataOrigin.SYNTHETIC;
    private static final CompensatorySaccadeTrialContext POLICY =
            new CompensatorySaccadeTrialContext(SESSION, CONFIGURATION, 3, 500, 3000);

    private final CompensatorySaccadeTrialMetricsCalculator calculator = new CompensatorySaccadeTrialMetricsCalculator();

    @Test void countsConfirmedTargetsAndKeepsTheExactMedianIncludingConfirmationTime() {
        var results = List.of(result("late", Outcome.SUCCESS, 3000, 500L),
                result("early", Outcome.SUCCESS, 1001, 600L),
                result("timeout", Outcome.TIMEOUT, 3001, null), result("interrupted", Outcome.INTERRUPTED, 900, null));
        var summary = calculator.calculate(POLICY, ORIGIN, results);
        assertEquals("compensatory-saccade-trial-v1", summary.calculationVersion());
        assertEquals(SESSION, summary.therapySessionId());
        assertEquals(CONFIGURATION, summary.configurationId());
        assertEquals(3, summary.configurationVersion());
        assertEquals(ORIGIN, summary.dataOrigin());
        assertEquals(4, summary.reportedTrialCount());
        assertEquals(2, summary.reachedTargetCount());
        assertEquals(new BigDecimal("2000.5"), summary.medianTimeToTargetMs().orElseThrow());
    }

    @Test void validatesDwellAndTerminalDeadlineWithoutTurningInterruptionsIntoSuccesses() {
        var deadline = result("at-deadline", Outcome.SUCCESS, 3000, 500L);
        assertEquals(new BigDecimal("3000"), calculator.calculate(POLICY, ORIGIN, List.of(deadline))
                .medianTimeToTargetMs().orElseThrow());
        assertDoesNotThrow(() -> POLICY.validate(result("timeout", Outcome.TIMEOUT, 3000, null)));
        assertDoesNotThrow(() -> POLICY.validate(result("interrupted", Outcome.INTERRUPTED, 2999, null)));
        for (var invalid : List.of(result("short-dwell", Outcome.SUCCESS, 1000, 499L),
                result("late-success", Outcome.SUCCESS, 3001, 500L),
                result("early-timeout", Outcome.TIMEOUT, 2999, null),
                result("late-interruption", Outcome.INTERRUPTED, 3000, null)))
            assertThrows(IllegalArgumentException.class, () -> calculator.calculate(POLICY, ORIGIN, List.of(invalid)));
    }

    @Test void rejectsInconsistentResultShapeAndPolicy() {
        assertThrows(IllegalArgumentException.class, () -> result("attempt", Outcome.SUCCESS, 1000, null));
        assertThrows(IllegalArgumentException.class, () -> result("attempt", Outcome.SUCCESS, 1000, 1001L));
        assertThrows(IllegalArgumentException.class, () -> result("attempt", Outcome.TIMEOUT, 3000, 500L));
        assertThrows(IllegalArgumentException.class, () -> result("attempt", Outcome.INTERRUPTED, 100, 50L));
        assertThrows(IllegalArgumentException.class, () -> result("attempt", Outcome.INTERRUPTED, -1, null));
        assertThrows(IllegalArgumentException.class, () -> result(" ", Outcome.INTERRUPTED, 100, null));
        assertThrows(IllegalArgumentException.class, () -> new CompensatorySaccadeTrialContext(SESSION, CONFIGURATION, 0, 500, 3000));
        assertThrows(IllegalArgumentException.class, () -> new CompensatorySaccadeTrialContext(SESSION, CONFIGURATION, 3, 0, 3000));
        assertThrows(IllegalArgumentException.class, () -> new CompensatorySaccadeTrialContext(SESSION, CONFIGURATION, 3, 500, 499));
    }

    @Test void identicalDuplicatesCountOnceAndConflictsRejectTheWholeCalculation() {
        var success = result("attempt", Outcome.SUCCESS, 1200, 500L);
        var duplicate = result("attempt", Outcome.SUCCESS, 1200, 500L);
        var timeout = result("other", Outcome.TIMEOUT, 3000, null);
        var expected = calculator.calculate(POLICY, ORIGIN, List.of(success, timeout));
        assertEquals(expected, calculator.calculate(POLICY, ORIGIN, List.of(timeout, success, duplicate, timeout)));
        var conflict = result("attempt", Outcome.SUCCESS, 1300, 500L);
        for (var input : List.of(List.of(success, conflict), List.of(conflict, success)))
            assertThrows(IllegalArgumentException.class, () -> calculator.calculate(POLICY, ORIGIN, input));
        assertEquals(expected, calculator.calculate(POLICY, ORIGIN, List.of(success, timeout)));
    }

    @Test void emptyOrUnsuccessfulEvidenceHasNoMedian() {
        var empty = calculator.calculate(POLICY, ORIGIN, List.of());
        assertEquals(0, empty.reportedTrialCount());
        assertEquals(0, empty.reachedTargetCount());
        assertTrue(empty.medianTimeToTargetMs().isEmpty());
        var unsuccessful = calculator.calculate(POLICY, ORIGIN,
                List.of(result("timeout", Outcome.TIMEOUT, 3000, null), result("interrupted", Outcome.INTERRUPTED, 0, null)));
        assertEquals(2, unsuccessful.reportedTrialCount());
        assertEquals(0, unsuccessful.reachedTargetCount());
        assertTrue(unsuccessful.medianTimeToTargetMs().isEmpty());
    }

    @Test void rejectsCrossSessionAndMixedOriginResultsWithoutExposingIdentifiers() {
        var foreign = new CompensatorySaccadeTrialResult(new UUID(0, 99), "foreign-attempt", ORIGIN, Outcome.SUCCESS, 1000, 500L);
        var otherOrigin = new CompensatorySaccadeTrialResult(SESSION, "other-origin", DataOrigin.PRODUCER_REPORTED,
                Outcome.SUCCESS, 1000, 500L);
        var own = result("own-attempt", Outcome.SUCCESS, 1000, 500L);
        for (var invalid : List.of(foreign, otherOrigin)) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> calculator.calculate(POLICY, ORIGIN, List.of(own, invalid)));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(invalid.trialId()));
            assertFalse(invalid.toString().contains(invalid.therapySessionId().toString()));
            assertFalse(POLICY.toString().contains(CONFIGURATION.toString()));
        }
        assertFalse(calculator.calculate(POLICY, ORIGIN, List.of(own)).toString().contains(SESSION.toString()));
    }

    private static CompensatorySaccadeTrialResult result(String id, Outcome outcome, long elapsed, Long dwell) {
        return new CompensatorySaccadeTrialResult(SESSION, id, ORIGIN, outcome, elapsed, dwell);
    }
}
