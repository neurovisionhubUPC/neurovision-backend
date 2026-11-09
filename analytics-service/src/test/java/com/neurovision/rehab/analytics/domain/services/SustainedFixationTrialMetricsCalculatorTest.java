package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin.*;
import static com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class SustainedFixationTrialMetricsCalculatorTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final UUID CONFIGURATION = new UUID(0, 2);
    private final SustainedFixationTrialContext context =
            new SustainedFixationTrialContext(SESSION, CONFIGURATION, 3, 1000, 3000);
    private final SustainedFixationTrialMetricsCalculator calculator = new SustainedFixationTrialMetricsCalculator();

    @Test void totalsAllValidInsideTimeButCountsOnlyCompletedTargets() {
        for (var origin : DataOrigin.values()) {
            var success = result("success", SUCCESS, 1500, 1200, 1000, origin);
            var timeout = result("timeout", TIMEOUT, 3000, 1100, 600, origin);
            var interrupted = result("interrupted", INTERRUPTED, 900, 400, 400, origin);
            var summary = calculator.calculate(context, origin, List.of(success, timeout, interrupted));
            assertEquals("sustained-fixation-trial-v1", summary.calculationVersion());
            assertEquals(SESSION, summary.therapySessionId());
            assertEquals(CONFIGURATION, summary.configurationId());
            assertEquals(3, summary.configurationVersion());
            assertEquals(origin, summary.dataOrigin());
            assertEquals(3, summary.reportedTrialCount());
            assertEquals(2700, summary.totalInsideTargetMs());
            assertEquals(1, summary.completedTargetCount());
            assertEquals(summary, calculator.calculate(context, origin, List.of(interrupted, success, timeout, success)));
        }
    }

    @Test void completesExactlyAtDeadlineAndIncludesZeroTimeAttempts() {
        var summary = calculator.calculate(context, SYNTHETIC, List.of(
                result("deadline", SUCCESS, 3000, 1000, 1000, SYNTHETIC),
                result("no-tracking", TIMEOUT, 3000, 0, 0, SYNTHETIC),
                result("immediate-stop", INTERRUPTED, 0, 0, 0, SYNTHETIC)));
        assertEquals(3, summary.reportedTrialCount());
        assertEquals(1000, summary.totalInsideTargetMs());
        assertEquals(1, summary.completedTargetCount());
    }

    @Test void emptyInputReportsNoAttemptsWithArithmeticZeroTotals() {
        var summary = calculator.calculate(context, SYNTHETIC, List.of());
        assertEquals(0, summary.reportedTrialCount());
        assertEquals(0, summary.totalInsideTargetMs());
        assertEquals(0, summary.completedTargetCount());
    }

    @Test void rejectsConflictingDuplicateInsteadOfReplacingIt() {
        var first = result("same", SUCCESS, 1500, 1200, 1000, SYNTHETIC);
        var conflict = result("same", SUCCESS, 1500, 1201, 1000, SYNTHETIC);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(context, SYNTHETIC, List.of(first, conflict)));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(context, SYNTHETIC, List.of(conflict, first)));
    }

    @Test void rejectsMixedSessionsOriginsAndInvalidResultsWithoutPartialTotals() {
        var valid = result("valid", SUCCESS, 1500, 1200, 1000, SYNTHETIC);
        var foreign = new SustainedFixationTrialResult(1, new UUID(0, 9), "foreign", SUCCESS, 1500, 1200, 1000, SYNTHETIC);
        var wrongOrigin = result("other-origin", SUCCESS, 1500, 1200, 1000, PRODUCER_REPORTED);
        var inconsistent = result("inconsistent", SUCCESS, 1500, 1100, 600, SYNTHETIC);
        for (var invalid : Arrays.asList(foreign, wrongOrigin, inconsistent, null)) {
            assertThrows(IllegalArgumentException.class,
                    () -> calculator.calculate(context, SYNTHETIC, Arrays.asList(valid, invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(null, SYNTHETIC, List.of(valid)));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(context, null, List.of(valid)));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(context, SYNTHETIC, null));
    }

    private static SustainedFixationTrialResult result(String id, Outcome outcome,
            long elapsed, long inside, long continuous, DataOrigin origin) {
        return new SustainedFixationTrialResult(1, SESSION, id, outcome, elapsed, inside, continuous, origin);
    }
}
