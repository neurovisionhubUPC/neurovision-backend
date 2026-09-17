package com.neurovision.rehab.telemetry.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TelemetrySequencePolicyTest {
    @Test
    void classifiesFirstContiguousGapAndLateArrivals() {
        assertEquals(TelemetrySequencePolicy.Quality.INITIAL,
                TelemetrySequencePolicy.assess(-1, 8).quality());
        assertEquals(TelemetrySequencePolicy.Quality.IN_ORDER,
                TelemetrySequencePolicy.assess(8, 9).quality());
        var gap = TelemetrySequencePolicy.assess(9, 12);
        assertEquals(TelemetrySequencePolicy.Quality.GAP_DETECTED, gap.quality());
        assertEquals(10L, gap.missingStart());
        assertEquals(11L, gap.missingEnd());
        var late = TelemetrySequencePolicy.assess(12, 10);
        assertEquals(TelemetrySequencePolicy.Quality.LATE_ARRIVAL, late.quality());
        assertNull(late.missingStart());
        assertNull(late.missingEnd());
    }

    @Test
    void boundarySequencesDoNotOverflow() {
        var gap = TelemetrySequencePolicy.assess(0, Long.MAX_VALUE);
        assertEquals(1L, gap.missingStart());
        assertEquals(Long.MAX_VALUE - 1, gap.missingEnd());
        assertEquals(TelemetrySequencePolicy.Quality.LATE_ARRIVAL,
                TelemetrySequencePolicy.assess(Long.MAX_VALUE, 0).quality());
        assertThrows(IllegalArgumentException.class, () -> TelemetrySequencePolicy.assess(-2, 0));
        assertThrows(IllegalArgumentException.class, () -> TelemetrySequencePolicy.assess(0, -1));
    }
}
