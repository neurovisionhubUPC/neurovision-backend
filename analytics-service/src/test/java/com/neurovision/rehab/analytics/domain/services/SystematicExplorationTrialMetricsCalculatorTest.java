package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystematicExplorationTrialMetricsCalculatorTest {
    private static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    private static final DataOrigin ORIGIN = DataOrigin.SYNTHETIC;
    private static final SystematicExplorationTrialContext POLICY =
            new SystematicExplorationTrialContext(SESSION, CONFIGURATION, 3, 500, 3000);
    private final SystematicExplorationTrialMetricsCalculator calculator = new SystematicExplorationTrialMetricsCalculator();

    private static List<Visit> visits(int count) {
        var result = new ArrayList<Visit>();
        for (int i = 0; i < count; i++) result.add(new Visit(Quadrant.values()[i], 1000, 500));
        return result;
    }
    private static SystematicExplorationTrialResult result(String id, Outcome outcome, long elapsed, List<Visit> visits) {
        return new SystematicExplorationTrialResult(SESSION, id, ORIGIN, outcome, elapsed, visits);
    }
    private SystematicExplorationTrialMetricsCalculator.Summary calculate(SystematicExplorationTrialResult... results) {
        return calculator.calculate(POLICY, ORIGIN, List.of(results));
    }

    @Test void reportsOnlyPerTraversalCountCoverageAndDurationWithFrozenProvenance() {
        var complete = result("complete", Outcome.COMPLETED, 4000, visits(4));
        var partial = result("partial", Outcome.TIMED_OUT, 5000, visits(2));
        var interrupted = result("interrupted", Outcome.INTERRUPTED, 1500, visits(1));
        var summary = calculate(partial, complete, interrupted);
        assertEquals("systematic-exploration-trial-v1", summary.calculationVersion());
        assertEquals(SESSION, summary.therapySessionId());
        assertEquals(CONFIGURATION, summary.configurationId());
        assertEquals(3, summary.configurationVersion());
        assertEquals(ORIGIN, summary.dataOrigin());
        assertEquals(3, summary.reportedTrialCount());
        assertEquals(List.of("complete", "interrupted", "partial"),
                summary.traversals().stream().map(t -> t.trialId()).toList());
        assertEquals(List.of(4, 1, 2), summary.traversals().stream().map(t -> t.visitedRegionCount()).toList());
        assertEquals(List.of(100, 25, 50), summary.traversals().stream().map(t -> t.coveragePercent()).toList());
        assertEquals(List.of(4000L, 1500L, 5000L), summary.traversals().stream().map(t -> t.elapsedMs()).toList());
        assertEquals(List.of(Outcome.COMPLETED, Outcome.INTERRUPTED, Outcome.TIMED_OUT),
                summary.traversals().stream().map(t -> t.outcome()).toList());
        assertEquals(partial, result("partial", Outcome.TIMED_OUT, 5000, visits(2)));
    }

    @Test void eachCoverageValueIsAnExactMultipleOfTwentyFiveWithoutInventingMissingRegions() {
        for (int count = 0; count < 4; count++) {
            var metric = calculate(result("attempt", Outcome.TIMED_OUT, count * 1000L + 3000, visits(count)))
                    .traversals().getFirst();
            assertEquals(count, metric.visitedRegionCount());
            assertEquals(count * 25, metric.coveragePercent());
        }
        var empty = calculate();
        assertEquals(0, empty.reportedTrialCount());
        assertTrue(empty.traversals().isEmpty());
    }

    @Test void qualifyingVisitAtExactDeadlineWinsAndLossTimeDoesNotExtendIt() {
        var atDeadline = new ArrayList<Visit>();
        for (var quadrant : Quadrant.values()) atDeadline.add(new Visit(quadrant, 3000, 500));
        assertDoesNotThrow(() -> POLICY.validate(result("complete", Outcome.COMPLETED, 12000, atDeadline)));
        assertDoesNotThrow(() -> POLICY.validate(result("timeout", Outcome.TIMED_OUT, 3000, List.of())));
        assertDoesNotThrow(() -> POLICY.validate(result("stop", Outcome.INTERRUPTED, 2999, List.of())));
        for (var visit : List.of(new Visit(Quadrant.TOP_LEFT, 3001, 500), new Visit(Quadrant.TOP_LEFT, 2000, 499))) {
            var invalid = result("invalid", Outcome.INTERRUPTED, visit.timeToVisitMs(), List.of(visit));
            assertThrows(IllegalArgumentException.class, () -> calculate(invalid));
        }
    }

    @Test void terminalDurationMustIncludeExactlyTheConfirmedPrefixAndCurrentQuadrant() {
        for (var bad : List.of(
                result("short-complete", Outcome.COMPLETED, 3999, visits(4)),
                result("long-complete", Outcome.COMPLETED, 4001, visits(4)),
                result("early-timeout", Outcome.TIMED_OUT, 4999, visits(2)),
                result("late-timeout", Outcome.TIMED_OUT, 5001, visits(2)),
                result("late-stop", Outcome.INTERRUPTED, 5000, visits(2)),
                result("negative-remainder", Outcome.INTERRUPTED, 1999, visits(2)),
                result("incomplete", Outcome.COMPLETED, 3000, visits(3)),
                result("full-timeout", Outcome.TIMED_OUT, 7000, visits(4)),
                result("full-stop", Outcome.INTERRUPTED, 4000, visits(4)),
                result("overflow", Outcome.INTERRUPTED, Long.MAX_VALUE, List.of())))
            assertThrows(IllegalArgumentException.class, () -> calculate(bad));
        assertDoesNotThrow(() -> calculate(result("instant-stop", Outcome.INTERRUPTED, 0, List.of())));
        assertDoesNotThrow(() -> calculate(result("after-visit", Outcome.INTERRUPTED, 1000, visits(1))));
    }

    @Test void rejectsSkippedRepeatedOrOutOfOrderQuadrantsAndMalformedVisits() {
        for (var invalid : List.of(
                List.of(new Visit(Quadrant.TOP_RIGHT, 1000, 500)),
                List.of(new Visit(Quadrant.TOP_LEFT, 1000, 500), new Visit(Quadrant.TOP_LEFT, 1000, 500)),
                List.of(new Visit(Quadrant.TOP_LEFT, 1000, 500), new Visit(Quadrant.BOTTOM_LEFT, 1000, 500)))) {
            assertThrows(IllegalArgumentException.class, () -> result("bad-order", Outcome.INTERRUPTED, 2000, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> result("oversize", Outcome.COMPLETED, 5000,
                List.of(new Visit(Quadrant.TOP_LEFT, 1000, 500), new Visit(Quadrant.TOP_RIGHT, 1000, 500),
                        new Visit(Quadrant.BOTTOM_LEFT, 1000, 500), new Visit(Quadrant.BOTTOM_RIGHT, 1000, 500),
                        new Visit(Quadrant.BOTTOM_RIGHT, 1000, 500))));
        assertThrows(IllegalArgumentException.class, () -> new Visit(null, 1000, 500));
        assertThrows(IllegalArgumentException.class, () -> new Visit(Quadrant.TOP_LEFT, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Visit(Quadrant.TOP_LEFT, 500, 501));
        assertThrows(IllegalArgumentException.class, () -> new Visit(Quadrant.TOP_LEFT, 500, -1));
    }

    @Test void fixedQuadrantsResolveCenterLinesAndRejectInvalidCoordinates() {
        assertEquals(Quadrant.TOP_LEFT, Quadrant.fromNormalized(0, 0));
        assertEquals(Quadrant.TOP_RIGHT, Quadrant.fromNormalized(0.5, 0));
        assertEquals(Quadrant.BOTTOM_LEFT, Quadrant.fromNormalized(0, 0.5));
        assertEquals(Quadrant.BOTTOM_RIGHT, Quadrant.fromNormalized(0.5, 0.5));
        assertEquals(Quadrant.BOTTOM_RIGHT, Quadrant.fromNormalized(1, 1));
        for (double invalid : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Quadrant.fromNormalized(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> Quadrant.fromNormalized(0, invalid));
        }
    }

    @Test void exactDuplicatesCountOnceWhileConflictsRejectTheWholeCalculation() {
        var a = result("same", Outcome.INTERRUPTED, 1500, visits(1));
        var duplicate = result("same", Outcome.INTERRUPTED, 1500, visits(1));
        var b = result("other", Outcome.TIMED_OUT, 3000, List.of());
        assertEquals(calculate(a, b), calculate(b, a, duplicate, b));
        var conflict = result("same", Outcome.INTERRUPTED, 1600, visits(1));
        assertThrows(IllegalArgumentException.class, () -> calculate(a, conflict));
        assertThrows(IllegalArgumentException.class, () -> calculate(conflict, a));
        assertEquals(1, calculate(a).reportedTrialCount());
    }

    @Test void rejectsMixedSessionsAndOriginsWithoutExposingIdentifiers() {
        var otherSession = new SystematicExplorationTrialResult(new UUID(0, 99), "foreign", ORIGIN,
                Outcome.INTERRUPTED, 0, List.of());
        var otherOrigin = new SystematicExplorationTrialResult(SESSION, "other-origin", DataOrigin.PRODUCER_REPORTED,
                Outcome.INTERRUPTED, 0, List.of());
        for (var invalid : List.of(otherSession, otherOrigin)) {
            var error = assertThrows(IllegalArgumentException.class, () -> calculate(invalid));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(invalid.trialId()));
            assertFalse(invalid.toString().contains(SESSION.toString()));
        }
        var producer = calculator.calculate(POLICY, DataOrigin.PRODUCER_REPORTED, List.of(otherOrigin));
        assertEquals(DataOrigin.PRODUCER_REPORTED, producer.dataOrigin());
        assertFalse(producer.toString().contains(SESSION.toString()));
        assertFalse(producer.traversals().getFirst().toString().contains("other-origin"));
        assertFalse(POLICY.toString().contains(CONFIGURATION.toString()));
    }

    @Test void defensivelyCopiesInputAndOutputLists() {
        var mutable = new ArrayList<>(visits(1));
        var saved = result("immutable", Outcome.INTERRUPTED, 1500, mutable);
        mutable.clear();
        assertEquals(1, saved.visits().size());
        assertThrows(UnsupportedOperationException.class, () -> saved.visits().clear());
        var summary = calculate(saved);
        assertThrows(UnsupportedOperationException.class, () -> summary.traversals().clear());
    }

    @Test void rejectsMissingDataAndInvalidFrozenPolicyWithoutExternalLookups() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(null, ORIGIN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(POLICY, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(POLICY, ORIGIN, null));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(POLICY, ORIGIN, Arrays.asList((SystematicExplorationTrialResult) null)));
        assertThrows(IllegalArgumentException.class, () -> result(" ", Outcome.INTERRUPTED, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> result("null", Outcome.INTERRUPTED, 0, null));
        assertThrows(IllegalArgumentException.class, () -> result("null", Outcome.INTERRUPTED, 0, Arrays.asList((Visit) null)));
        assertThrows(IllegalArgumentException.class, () -> result("negative", Outcome.INTERRUPTED, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialResult(null, "trial", ORIGIN, Outcome.INTERRUPTED, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> result("null", null, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialResult(SESSION, "trial", null, Outcome.INTERRUPTED, 0, List.of()));
        for (long[] values : new long[][]{{0, 500, 3000}, {1, 299, 3000}, {1, 2001, 3000}, {1, 500, 1999}, {1, 500, 10001}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SystematicExplorationTrialContext(SESSION, CONFIGURATION, values[0], values[1], values[2]));
        }
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialContext(null, CONFIGURATION, 1, 500, 3000));
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialContext(SESSION, null, 1, 500, 3000));
    }
}
