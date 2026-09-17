package com.neurovision.rehab.telemetry.domain.services;

/** Classifies arrival order without discarding a unique, valid batch. */
public final class TelemetrySequencePolicy {
    private TelemetrySequencePolicy() {}

    public static Assessment assess(long highestSequence, long receivedSequence) {
        if (highestSequence < -1 || receivedSequence < 0) {
            throw new IllegalArgumentException("Invalid telemetry sequence");
        }
        if (highestSequence == -1) {
            return new Assessment(Quality.INITIAL, null, null);
        }
        if (receivedSequence <= highestSequence) {
            return new Assessment(Quality.LATE_ARRIVAL, null, null);
        }
        if (receivedSequence - highestSequence > 1) {
            return new Assessment(Quality.GAP_DETECTED, highestSequence + 1, receivedSequence - 1);
        }
        return new Assessment(Quality.IN_ORDER, null, null);
    }

    public enum Quality { INITIAL, IN_ORDER, GAP_DETECTED, LATE_ARRIVAL }

    public record Assessment(Quality quality, Long missingStart, Long missingEnd) {}
}
