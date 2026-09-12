package com.neurovision.rehab.telemetry.application.internal.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GameplaySample;
import com.neurovision.rehab.telemetry.domain.model.valueobjects.TelemetryBatch.GazeSample;

class TelemetryBatchValidatorTest {

    private static final Instant SERVER_RECEIVED_AT = Instant.parse("2026-09-12T12:00:10Z");
    private static final TelemetryValidationPolicy POLICY =
            new TelemetryValidationPolicy(3, Duration.ofSeconds(5));
    private final TelemetryBatchValidator validator = new TelemetryBatchValidator();

    @Test
    void acceptsBoundaryValuesAndAddsServerReceivedTime() {
        TelemetryBatch batch = new BatchBuilder()
                .gazeSamples(List.of(
                        new GazeSample(0, 0, 1, 0),
                        new GazeSample(0, 1, 0, 1)))
                .gameplaySamples(List.of(
                        new GameplaySample(0, 0, 1, 1, 0, 0, 0, 1)))
                .build();

        TelemetryValidationResult result = validator.validate(batch, POLICY, SERVER_RECEIVED_AT);

        assertTrue(result.isAccepted());
        assertSame(batch, result.batch());
        assertEquals(SERVER_RECEIVED_AT, result.serverReceivedAt());
        assertTrue(result.rejection().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> batch.gazeSamples().add(new GazeSample(1, 0.5, 0.5, 0.5)));
    }

    @Test
    void acceptsSentTimeExactlyAtAllowedFutureClockSkew() {
        TelemetryBatch batch = new BatchBuilder()
                .sentAt(SERVER_RECEIVED_AT.plusSeconds(5))
                .build();

        TelemetryValidationResult result = validator.validate(batch, POLICY, SERVER_RECEIVED_AT);

        assertTrue(result.isAccepted());
    }

    @Test
    void acceptsExtremeAllowedClockSkewWithoutOverflow() {
        TelemetryBatch batch = new BatchBuilder()
                .sentAt(Instant.MAX)
                .build();
        TelemetryValidationPolicy extremePolicy =
                new TelemetryValidationPolicy(3, Duration.ofSeconds(Long.MAX_VALUE));

        TelemetryValidationResult result = validator.validate(batch, extremePolicy, SERVER_RECEIVED_AT);

        assertTrue(result.isAccepted());
    }

    @Test
    void rejectsNullBatchWithoutLeakingInput() {
        TelemetryValidationResult result = validator.validate(null, POLICY, SERVER_RECEIVED_AT);

        assertRejection(result, TelemetryRejectionCategory.UNSUPPORTED_SCHEMA_VERSION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBatchCases")
    void rejectsInvalidBatchDeterministically(InvalidCase invalidCase) {
        BatchBuilder builder = new BatchBuilder();
        invalidCase.mutation().accept(builder);

        TelemetryValidationResult result = validator.validate(builder.build(), POLICY, SERVER_RECEIVED_AT);

        assertRejection(result, invalidCase.category());
    }

    @Test
    void appliesMetadataValidationBeforeContentValidation() {
        TelemetryBatch invalidInSeveralWays = new BatchBuilder()
                .schemaVersion(2)
                .gazeSamples(List.of())
                .gameplaySamples(List.of())
                .build();

        assertRejection(
                validator.validate(invalidInSeveralWays, POLICY, SERVER_RECEIVED_AT),
                TelemetryRejectionCategory.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void requiresExplicitSanePolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new TelemetryValidationPolicy(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new TelemetryValidationPolicy(1, Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class,
                () -> new TelemetryValidationPolicy(1, null));
    }

    private static Stream<InvalidCase> invalidBatchCases() {
        return Stream.of(
                invalid("unsupported schema", TelemetryRejectionCategory.UNSUPPORTED_SCHEMA_VERSION,
                        builder -> builder.schemaVersion(2)),
                invalid("missing batch id", TelemetryRejectionCategory.MISSING_BATCH_ID,
                        builder -> builder.batchId(null)),
                invalid("missing therapy session id", TelemetryRejectionCategory.MISSING_THERAPY_SESSION_ID,
                        builder -> builder.therapySessionId(null)),
                invalid("missing stream id", TelemetryRejectionCategory.MISSING_STREAM_ID,
                        builder -> builder.streamId(null)),
                invalid("negative sequence", TelemetryRejectionCategory.NEGATIVE_SEQUENCE,
                        builder -> builder.sequence(-1)),
                invalid("missing captured time", TelemetryRejectionCategory.MISSING_CAPTURED_AT,
                        builder -> builder.capturedAt(null)),
                invalid("missing sent time", TelemetryRejectionCategory.MISSING_SENT_AT,
                        builder -> builder.sentAt(null)),
                invalid("captured after sent", TelemetryRejectionCategory.CAPTURED_AFTER_SENT,
                        builder -> builder.capturedAt(Instant.parse("2026-09-12T12:00:06Z"))),
                invalid("future clock skew", TelemetryRejectionCategory.CLIENT_TIME_TOO_FAR_IN_FUTURE,
                        builder -> builder.sentAt(Instant.parse("2026-09-12T12:00:16Z"))),
                invalid("missing gaze list", TelemetryRejectionCategory.MISSING_GAZE_SAMPLES,
                        builder -> builder.gazeSamples(null)),
                invalid("missing gameplay list", TelemetryRejectionCategory.MISSING_GAMEPLAY_SAMPLES,
                        builder -> builder.gameplaySamples(null)),
                invalid("empty batch", TelemetryRejectionCategory.EMPTY_BATCH,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of())),
                invalid("too many samples", TelemetryRejectionCategory.TOO_MANY_SAMPLES,
                        builder -> builder.gazeSamples(List.of(
                                gaze(0), gaze(1), gaze(2), gaze(3)))),
                invalid("null gaze element", TelemetryRejectionCategory.NULL_GAZE_SAMPLE,
                        builder -> builder.gazeSamples(Arrays.asList((GazeSample) null))),
                invalid("negative gaze offset", TelemetryRejectionCategory.NEGATIVE_GAZE_OFFSET,
                        builder -> builder.gazeSamples(List.of(new GazeSample(-1, 0.5, 0.5, 0.5)))),
                invalid("gaze offsets out of order", TelemetryRejectionCategory.GAZE_OFFSETS_OUT_OF_ORDER,
                        builder -> builder.gazeSamples(List.of(gaze(2), gaze(1)))),
                invalid("gaze x below range", TelemetryRejectionCategory.INVALID_GAZE_X,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, -0.01, 0.5, 0.5)))),
                invalid("gaze x above range", TelemetryRejectionCategory.INVALID_GAZE_X,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, 1.01, 0.5, 0.5)))),
                invalid("gaze x NaN", TelemetryRejectionCategory.INVALID_GAZE_X,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, Double.NaN, 0.5, 0.5)))),
                invalid("gaze x infinite", TelemetryRejectionCategory.INVALID_GAZE_X,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, Double.POSITIVE_INFINITY, 0.5, 0.5)))),
                invalid("gaze y invalid", TelemetryRejectionCategory.INVALID_GAZE_Y,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, 0.5, -0.01, 0.5)))),
                invalid("gaze confidence invalid", TelemetryRejectionCategory.INVALID_GAZE_CONFIDENCE,
                        builder -> builder.gazeSamples(List.of(new GazeSample(0, 0.5, 0.5, Double.NaN)))),
                invalid("null gameplay element", TelemetryRejectionCategory.NULL_GAMEPLAY_SAMPLE,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(Arrays.asList((GameplaySample) null))),
                invalid("negative gameplay offset", TelemetryRejectionCategory.NEGATIVE_GAMEPLAY_OFFSET,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(gameplay(-1)))),
                invalid("gameplay offsets out of order", TelemetryRejectionCategory.GAMEPLAY_OFFSETS_OUT_OF_ORDER,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(gameplay(2), gameplay(1)))),
                invalid("player x invalid", TelemetryRejectionCategory.INVALID_PLAYER_X,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, -0.01, 0.5, 0.5, 0.5, 1, 1, 0.5)))),
                invalid("player y invalid", TelemetryRejectionCategory.INVALID_PLAYER_Y,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, Double.NaN, 0.5, 0.5, 1, 1, 0.5)))),
                invalid("target x invalid", TelemetryRejectionCategory.INVALID_TARGET_X,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 1.01, 0.5, 1, 1, 0.5)))),
                invalid("target y invalid", TelemetryRejectionCategory.INVALID_TARGET_Y,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, Double.POSITIVE_INFINITY, 1, 1, 0.5)))),
                invalid("negative score", TelemetryRejectionCategory.NEGATIVE_SCORE,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, 0.5, -1, 1, 0.5)))),
                invalid("negative speed", TelemetryRejectionCategory.INVALID_SPEED,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, 0.5, 1, -0.01, 0.5)))),
                invalid("speed NaN", TelemetryRejectionCategory.INVALID_SPEED,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, 0.5, 1, Double.NaN, 0.5)))),
                invalid("speed infinite", TelemetryRejectionCategory.INVALID_SPEED,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, 0.5, 1, Double.POSITIVE_INFINITY, 0.5)))),
                invalid("progress invalid", TelemetryRejectionCategory.INVALID_PROGRESS,
                        builder -> builder.gazeSamples(List.of()).gameplaySamples(List.of(
                                new GameplaySample(0, 0.5, 0.5, 0.5, 0.5, 1, 1, 1.01)))));
    }

    private static InvalidCase invalid(
            String name,
            TelemetryRejectionCategory category,
            Consumer<BatchBuilder> mutation) {
        return new InvalidCase(name, category, mutation);
    }

    private static GazeSample gaze(long offset) {
        return new GazeSample(offset, 0.5, 0.5, 0.5);
    }

    private static GameplaySample gameplay(long offset) {
        return new GameplaySample(offset, 0.5, 0.5, 0.5, 0.5, 1, 1, 0.5);
    }

    private static void assertRejection(
            TelemetryValidationResult result,
            TelemetryRejectionCategory expectedCategory) {
        assertFalse(result.isAccepted());
        assertEquals(expectedCategory, result.rejection().orElseThrow());
        assertEquals(null, result.batch());
        assertEquals(null, result.serverReceivedAt());
    }

    private record InvalidCase(
            String name,
            TelemetryRejectionCategory category,
            Consumer<BatchBuilder> mutation) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final class BatchBuilder {
        private int schemaVersion = 1;
        private UUID batchId = UUID.fromString("51da9973-f3b6-4b86-8a84-25762ccf89ee");
        private UUID therapySessionId = UUID.fromString("3f1a860f-cc55-40d9-b86b-377780949b82");
        private UUID streamId = UUID.fromString("cc192a52-6b60-436e-8ba5-89b530da5d22");
        private long sequence = 0;
        private Instant capturedAt = Instant.parse("2026-09-12T12:00:00Z");
        private Instant sentAt = Instant.parse("2026-09-12T12:00:05Z");
        private List<GazeSample> gazeSamples = new ArrayList<>(List.of(gaze(0)));
        private List<GameplaySample> gameplaySamples = new ArrayList<>();

        BatchBuilder schemaVersion(int value) {
            schemaVersion = value;
            return this;
        }

        BatchBuilder batchId(UUID value) {
            batchId = value;
            return this;
        }

        BatchBuilder therapySessionId(UUID value) {
            therapySessionId = value;
            return this;
        }

        BatchBuilder streamId(UUID value) {
            streamId = value;
            return this;
        }

        BatchBuilder sequence(long value) {
            sequence = value;
            return this;
        }

        BatchBuilder capturedAt(Instant value) {
            capturedAt = value;
            return this;
        }

        BatchBuilder sentAt(Instant value) {
            sentAt = value;
            return this;
        }

        BatchBuilder gazeSamples(List<GazeSample> value) {
            gazeSamples = value;
            return this;
        }

        BatchBuilder gameplaySamples(List<GameplaySample> value) {
            gameplaySamples = value;
            return this;
        }

        TelemetryBatch build() {
            return new TelemetryBatch(
                    schemaVersion,
                    batchId,
                    therapySessionId,
                    streamId,
                    sequence,
                    capturedAt,
                    sentAt,
                    gazeSamples,
                    gameplaySamples);
        }
    }
}
