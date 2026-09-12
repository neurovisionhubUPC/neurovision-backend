package com.neurovision.rehab.telemetry.application.internal.validation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GameplaySample;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GazeSample;

public final class TelemetryBatchValidator {

    public TelemetryValidationResult validate(
            TelemetryBatch batch,
            TelemetryValidationPolicy policy,
            Instant serverReceivedAt) {
        Objects.requireNonNull(policy, "Validation policy must not be null");
        Objects.requireNonNull(serverReceivedAt, "Server received time must not be null");

        TelemetryRejectionCategory rejection = firstRejection(batch, policy, serverReceivedAt);
        return rejection == null
                ? TelemetryValidationResult.accepted(batch, serverReceivedAt)
                : TelemetryValidationResult.rejected(rejection);
    }

    private TelemetryRejectionCategory firstRejection(
            TelemetryBatch batch,
            TelemetryValidationPolicy policy,
            Instant serverReceivedAt) {
        if (batch == null || batch.schemaVersion() != 1) {
            return TelemetryRejectionCategory.UNSUPPORTED_SCHEMA_VERSION;
        }
        if (batch.batchId() == null) {
            return TelemetryRejectionCategory.MISSING_BATCH_ID;
        }
        if (batch.therapySessionId() == null) {
            return TelemetryRejectionCategory.MISSING_THERAPY_SESSION_ID;
        }
        if (batch.streamId() == null) {
            return TelemetryRejectionCategory.MISSING_STREAM_ID;
        }
        if (batch.sequence() < 0) {
            return TelemetryRejectionCategory.NEGATIVE_SEQUENCE;
        }
        if (batch.capturedAt() == null) {
            return TelemetryRejectionCategory.MISSING_CAPTURED_AT;
        }
        if (batch.sentAt() == null) {
            return TelemetryRejectionCategory.MISSING_SENT_AT;
        }
        if (batch.capturedAt().isAfter(batch.sentAt())) {
            return TelemetryRejectionCategory.CAPTURED_AFTER_SENT;
        }
        if (exceedsAllowedFutureClockSkew(
                batch.sentAt(), serverReceivedAt, policy.allowedFutureClockSkew())) {
            return TelemetryRejectionCategory.CLIENT_TIME_TOO_FAR_IN_FUTURE;
        }
        if (batch.gazeSamples() == null) {
            return TelemetryRejectionCategory.MISSING_GAZE_SAMPLES;
        }
        if (batch.gameplaySamples() == null) {
            return TelemetryRejectionCategory.MISSING_GAMEPLAY_SAMPLES;
        }
        if (batch.gazeSamples().isEmpty() && batch.gameplaySamples().isEmpty()) {
            return TelemetryRejectionCategory.EMPTY_BATCH;
        }
        if ((long) batch.gazeSamples().size() + batch.gameplaySamples().size() > policy.maximumSamples()) {
            return TelemetryRejectionCategory.TOO_MANY_SAMPLES;
        }

        TelemetryRejectionCategory gazeRejection = validateGazeSamples(batch.gazeSamples());
        return gazeRejection != null ? gazeRejection : validateGameplaySamples(batch.gameplaySamples());
    }

    private TelemetryRejectionCategory validateGazeSamples(List<GazeSample> samples) {
        long previousOffset = -1;
        for (GazeSample sample : samples) {
            if (sample == null) {
                return TelemetryRejectionCategory.NULL_GAZE_SAMPLE;
            }
            if (sample.offsetMs() < 0) {
                return TelemetryRejectionCategory.NEGATIVE_GAZE_OFFSET;
            }
            if (sample.offsetMs() < previousOffset) {
                return TelemetryRejectionCategory.GAZE_OFFSETS_OUT_OF_ORDER;
            }
            if (!isNormalized(sample.x())) {
                return TelemetryRejectionCategory.INVALID_GAZE_X;
            }
            if (!isNormalized(sample.y())) {
                return TelemetryRejectionCategory.INVALID_GAZE_Y;
            }
            if (!isNormalized(sample.confidence())) {
                return TelemetryRejectionCategory.INVALID_GAZE_CONFIDENCE;
            }
            previousOffset = sample.offsetMs();
        }
        return null;
    }

    private TelemetryRejectionCategory validateGameplaySamples(List<GameplaySample> samples) {
        long previousOffset = -1;
        for (GameplaySample sample : samples) {
            if (sample == null) {
                return TelemetryRejectionCategory.NULL_GAMEPLAY_SAMPLE;
            }
            if (sample.offsetMs() < 0) {
                return TelemetryRejectionCategory.NEGATIVE_GAMEPLAY_OFFSET;
            }
            if (sample.offsetMs() < previousOffset) {
                return TelemetryRejectionCategory.GAMEPLAY_OFFSETS_OUT_OF_ORDER;
            }
            if (!isNormalized(sample.playerX())) {
                return TelemetryRejectionCategory.INVALID_PLAYER_X;
            }
            if (!isNormalized(sample.playerY())) {
                return TelemetryRejectionCategory.INVALID_PLAYER_Y;
            }
            if (!isNormalized(sample.targetX())) {
                return TelemetryRejectionCategory.INVALID_TARGET_X;
            }
            if (!isNormalized(sample.targetY())) {
                return TelemetryRejectionCategory.INVALID_TARGET_Y;
            }
            if (sample.score() < 0) {
                return TelemetryRejectionCategory.NEGATIVE_SCORE;
            }
            if (!Double.isFinite(sample.speed()) || sample.speed() < 0) {
                return TelemetryRejectionCategory.INVALID_SPEED;
            }
            if (!isNormalized(sample.progress())) {
                return TelemetryRejectionCategory.INVALID_PROGRESS;
            }
            previousOffset = sample.offsetMs();
        }
        return null;
    }

    private boolean isNormalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private boolean exceedsAllowedFutureClockSkew(
            Instant sentAt,
            Instant serverReceivedAt,
            Duration allowedFutureClockSkew) {
        return sentAt.isAfter(serverReceivedAt)
                && Duration.between(serverReceivedAt, sentAt).compareTo(allowedFutureClockSkew) > 0;
    }
}
