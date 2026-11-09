package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class StandardScanningTrialMetricsCalculatorTest {
    private static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    private final StandardScanningTrialContext context = new StandardScanningTrialContext(SESSION, CONFIGURATION, 3, 500, 3000);
    private final StandardScanningTrialMetricsCalculator calculator = new StandardScanningTrialMetricsCalculator();

    private StandardScanningTrialResult result(String trial, Outcome outcome, long duration, Long dwell) {
        return new StandardScanningTrialResult(1, SESSION, trial, outcome, duration, dwell, DataOrigin.SYNTHETIC);
    }
    private StandardScanningTrialMetricsCalculator.Summary calculate(List<StandardScanningTrialResult> results) {
        return calculator.calculate(context, DataOrigin.SYNTHETIC, results);
    }

    @Test void calculatesOnlyTheReportedTrialsAndSuccessfulSearchMedian() {
        var summary = calculate(List.of(result("a", Outcome.SUCCESS, 1200, 500L),
                result("b", Outcome.SUCCESS, 1800, 500L), result("c", Outcome.TIMEOUT, 3000, null),
                result("d", Outcome.INTERRUPTED, 900, null)));
        assertEquals(SESSION, summary.therapySessionId()); assertEquals(CONFIGURATION, summary.configurationId());
        assertEquals(3, summary.configurationVersion()); assertEquals(DataOrigin.SYNTHETIC, summary.dataOrigin());
        assertEquals("standard-scanning-trial-v1", summary.calculationVersion());
        assertEquals(4, summary.reportedTrialCount()); assertEquals(2, summary.detectedTargetCount());
        assertEquals(1, summary.omissionCount()); assertEquals(1, summary.interruptedCount());
        assertEquals(2, summary.eligibleLocalizationCount());
        assertEquals(0, new BigDecimal("1500").compareTo(summary.medianLocalizationDurationMs().orElseThrow()));
    }

    @Test void identicalReplaysAndInputPermutationDoNotChangeCountsOrMutateInput() {
        var a = result("a", Outcome.SUCCESS, 1200, 500L); var b = result("b", Outcome.TIMEOUT, 3001, null);
        var input = new ArrayList<>(List.of(b, a, a)); var before = List.copyOf(input);
        var expected = calculate(List.of(a, b));
        assertEquals(expected, calculate(input)); assertEquals(before, input);
        assertEquals(expected, calculate(List.of(a, b, a, b)));
        assertEquals(expected, calculate(input)); // No retained state between calls.
    }

    @Test void conflictingFinalResultsAreRejectedRegardlessOfArrivalOrder() {
        var a = result("same", Outcome.SUCCESS, 1200, 500L);
        for (var changed : List.of(result("same", Outcome.SUCCESS, 1201, 500L),
                result("same", Outcome.SUCCESS, 1200, 600L), result("same", Outcome.TIMEOUT, 3000, null))) {
            for (var input : List.of(List.of(a, changed), List.of(changed, a))) {
                var error = assertThrows(IllegalArgumentException.class, () -> calculate(input));
                assertEquals("Conflicting Standard Scanning trial result", error.getMessage());
                assertFalse(error.getMessage().contains("same")); assertNull(error.getCause());
            }
        }
        assertEquals(1, calculate(List.of(a)).detectedTargetCount());
    }

    @Test void emptyOrUnsuccessfulEvidenceHasNoInventedMedian() {
        var empty = calculate(List.of());
        assertEquals(0, empty.reportedTrialCount()); assertEquals(0, empty.detectedTargetCount());
        assertEquals(0, empty.omissionCount()); assertEquals(0, empty.interruptedCount());
        assertEquals(0, empty.eligibleLocalizationCount()); assertTrue(empty.medianLocalizationDurationMs().isEmpty());
        var unsuccessful = calculate(List.of(result("a", Outcome.TIMEOUT, 3000, null), result("b", Outcome.INTERRUPTED, 0, null)));
        assertEquals(2, unsuccessful.reportedTrialCount()); assertEquals(0, unsuccessful.detectedTargetCount());
        assertEquals(1, unsuccessful.omissionCount()); assertEquals(1, unsuccessful.interruptedCount());
        assertTrue(unsuccessful.medianLocalizationDurationMs().isEmpty());
    }

    @Test void handlesOddEvenAndHalfMillisecondMediansWithoutRoundingOrOverflow() {
        assertEquals(0, new BigDecimal("1001").compareTo(calculate(List.of(result("a", Outcome.SUCCESS, 1001, 500L)))
                .medianLocalizationDurationMs().orElseThrow()));
        assertEquals(0, new BigDecimal("1000.5").compareTo(calculate(List.of(result("a", Outcome.SUCCESS, 1000, 500L),
                result("b", Outcome.SUCCESS, 1001, 500L))).medianLocalizationDurationMs().orElseThrow()));
        assertEquals(0, new BigDecimal("1001").compareTo(calculate(List.of(result("a", Outcome.SUCCESS, 1002, 500L),
                result("b", Outcome.SUCCESS, 1000, 500L), result("c", Outcome.SUCCESS, 1001, 500L)))
                .medianLocalizationDurationMs().orElseThrow()));
        var large = new StandardScanningTrialContext(SESSION, CONFIGURATION, 3, 500, Long.MAX_VALUE);
        var summary = calculator.calculate(large, DataOrigin.SYNTHETIC, List.of(result("a", Outcome.SUCCESS, Long.MAX_VALUE, 500L),
                result("b", Outcome.SUCCESS, Long.MAX_VALUE - 1, 500L)));
        assertEquals(new BigDecimal("9223372036854775806.5"), summary.medianLocalizationDurationMs().orElseThrow());
    }

    @Test void validatesTheFrozenDwellAndSearchDeadlineIncludingExactBoundaries() {
        assertEquals(2, calculate(List.of(result("a", Outcome.SUCCESS, 500, 500L),
                result("b", Outcome.SUCCESS, 3000, 500L))).detectedTargetCount());
        assertEquals(1, calculate(List.of(result("a", Outcome.TIMEOUT, 3000, null))).omissionCount());
        assertEquals(1, calculate(List.of(result("a", Outcome.INTERRUPTED, 2999, null))).interruptedCount());
        for (var invalid : List.of(result("a", Outcome.SUCCESS, 1200, 499L), result("a", Outcome.SUCCESS, 3001, 500L),
                result("a", Outcome.TIMEOUT, 2999, null), result("a", Outcome.INTERRUPTED, 3000, null)))
            assertThrows(IllegalArgumentException.class, () -> calculate(List.of(invalid)));
    }

    @Test void rejectsInvalidResultShapesBeforeAnyCalculation() {
        for (int kind = 0; kind < 10; kind++) {
            int k = kind;
            assertThrows(IllegalArgumentException.class, () -> { switch (k) {
                case 0 -> new StandardScanningTrialResult(2, SESSION, "a", Outcome.SUCCESS, 1200, 500L, DataOrigin.SYNTHETIC);
                case 1 -> new StandardScanningTrialResult(1, null, "a", Outcome.SUCCESS, 1200, 500L, DataOrigin.SYNTHETIC);
                case 2 -> result(null, Outcome.SUCCESS, 1200, 500L);
                case 3 -> result(" ", Outcome.SUCCESS, 1200, 500L);
                case 4 -> result("a", null, 1200, 500L);
                case 5 -> result("a", Outcome.SUCCESS, -1, 500L);
                case 6 -> result("a", Outcome.SUCCESS, 1200, null);
                case 7 -> result("a", Outcome.SUCCESS, 1200, 1201L);
                case 8 -> result("a", Outcome.TIMEOUT, 3000, 500L);
                default -> new StandardScanningTrialResult(1, SESSION, "a", Outcome.SUCCESS, 1200, 500L, null);
            } });
        }
        assertThrows(IllegalArgumentException.class, () -> result("a", Outcome.SUCCESS, 1200, -1L));
        assertThrows(IllegalArgumentException.class, () -> result("a", Outcome.INTERRUPTED, 0, 0L));
    }

    @Test void rejectsMixedSessionsAndOriginsInsteadOfProducingUnlabelledAggregates() {
        var a = result("a", Outcome.SUCCESS, 1200, 500L);
        var otherSession = new StandardScanningTrialResult(1, new UUID(0, 9), "b", Outcome.SUCCESS, 1200, 500L, DataOrigin.SYNTHETIC);
        var real = new StandardScanningTrialResult(1, SESSION, "b", Outcome.SUCCESS, 1200, 500L, DataOrigin.PRODUCER_REPORTED);
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(a, otherSession)));
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(a, real)));
        var reported = calculator.calculate(context, DataOrigin.PRODUCER_REPORTED, List.of(real));
        assertEquals(DataOrigin.PRODUCER_REPORTED, reported.dataOrigin()); assertEquals(1, reported.detectedTargetCount());
        assertEquals(DataOrigin.PRODUCER_REPORTED, calculator.calculate(context, DataOrigin.PRODUCER_REPORTED, List.of()).dataOrigin());
    }

    @Test void rejectsMissingContextAndInvalidPolicyWithoutRetainingResults() {
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialContext(null, CONFIGURATION, 3, 500, 3000));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialContext(SESSION, null, 3, 500, 3000));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialContext(SESSION, CONFIGURATION, 0, 500, 3000));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialContext(SESSION, CONFIGURATION, 3, 0, 3000));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialContext(SESSION, CONFIGURATION, 3, 500, 499));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(null, DataOrigin.SYNTHETIC, List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(context, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculate(null));
        assertThrows(IllegalArgumentException.class, () -> calculate(Arrays.asList((StandardScanningTrialResult) null)));
    }

    @Test void diagnosticsDoNotExposeTrialSessionOrConfigurationIdentities() {
        var a = result("private-trial-reference", Outcome.SUCCESS, 1200, 500L);
        for (String text : List.of(a.toString(), context.toString(), calculate(List.of(a)).toString())) {
            assertFalse(text.contains("private-trial-reference")); assertFalse(text.contains(SESSION.toString()));
            assertFalse(text.contains(CONFIGURATION.toString()));
        }
    }
}
