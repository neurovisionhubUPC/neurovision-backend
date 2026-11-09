package com.neurovision.rehab.analytics.domain.model.valueobjects;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin.SYNTHETIC;
import static com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class SustainedFixationTrialResultTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final UUID CONFIGURATION = new UUID(0, 2);

    @Test void rejectsInvalidIdentityVersionAndMissingMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(2, SESSION, "trial", SUCCESS, 1000, 1000, 1000, SYNTHETIC));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(1, null, "trial", SUCCESS, 1000, 1000, 1000, SYNTHETIC));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(1, SESSION, " ", SUCCESS, 1000, 1000, 1000, SYNTHETIC));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(1, SESSION, "trial", null, 1000, 1000, 1000, SYNTHETIC));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(1, SESSION, "trial", SUCCESS, 1000, 1000, 1000, null));
    }

    @Test void rejectsNegativeOrMisorderedDurations() {
        for (long[] times : new long[][]{{-1, 0, 0}, {1, -1, 0}, {1, 1, -1}, {1, 2, 1}, {2, 1, 2}}) {
            assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialResult(
                    1, SESSION, "trial", INTERRUPTED, times[0], times[1], times[2], SYNTHETIC));
        }
    }

    @Test void contextRequiresFrozenIdentityAndClinicalRanges() {
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialContext(null, CONFIGURATION, 1, 1000, 3000));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialContext(SESSION, null, 1, 1000, 3000));
        assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialContext(SESSION, CONFIGURATION, 0, 1000, 3000));
        for (long[] bounds : new long[][]{{299, 500}, {3001, 5000}, {300, 499}, {300, 5001}, {1000, 999}}) {
            assertThrows(IllegalArgumentException.class, () -> new SustainedFixationTrialContext(
                    SESSION, CONFIGURATION, 1, bounds[0], bounds[1]));
        }
        assertDoesNotThrow(() -> new SustainedFixationTrialContext(SESSION, CONFIGURATION, 1, 300, 500));
        assertDoesNotThrow(() -> new SustainedFixationTrialContext(SESSION, CONFIGURATION, 1, 3000, 5000));
        assertDoesNotThrow(() -> new SustainedFixationTrialContext(SESSION, CONFIGURATION, 1, 1000, 1000));
    }

    @Test void rejectsOutcomesInconsistentWithContinuousThresholdAndDeadline() {
        var context = new SustainedFixationTrialContext(SESSION, CONFIGURATION, 1, 1000, 3000);
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(SUCCESS, 1500, 1100, 600)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(SUCCESS, 3001, 1000, 1000)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(TIMEOUT, 2999, 500, 500)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(TIMEOUT, 3001, 500, 500)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(TIMEOUT, 3000, 1000, 1000)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(INTERRUPTED, 3000, 500, 500)));
        assertThrows(IllegalArgumentException.class, () -> context.validate(result(INTERRUPTED, 1500, 1000, 1000)));
    }

    private static SustainedFixationTrialResult result(VisualSearchTrialResult.Outcome outcome,
            long elapsed, long inside, long continuous) {
        return new SustainedFixationTrialResult(1, SESSION, "trial", outcome, elapsed, inside, continuous, SYNTHETIC);
    }
}
