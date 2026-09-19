package com.neurovision.rehab.analytics.domain.services;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

public final class BaselineTelemetryMetricsCalculator {
    private static final AnalyticsCalculationProfile PROFILE = AnalyticsCalculationProfile.BASELINE_V1;

    public BaselineStreamSummary calculate(List<AdmittedTelemetryBatch> admittedBatches) {
        if (admittedBatches == null || admittedBatches.isEmpty()) {
            throw new IllegalArgumentException("At least one admitted batch is required");
        }

        var batches = admittedBatches.stream()
                .map(batch -> Objects.requireNonNull(batch, "admitted batch"))
                .sorted(Comparator.comparingLong(batch -> batch.batch().sequence()))
                .toList();
        var first = batches.getFirst().batch();
        validateIdentity(first);
        validateBatches(batches, first.schemaVersion(), first.therapySessionId(), first.streamId());

        var confidence = new NumericAccumulator();
        var confidenceBins = new long[PROFILE.confidenceBinCount()];
        var heatmap = new long[PROFILE.gridRows() * PROFILE.gridColumns()];
        var occupiedCells = new HashSet<Integer>();
        var boundingBox = new BoundingBoxAccumulator();
        var pathDistance = new CompensatedSum();
        var velocity = new NumericAccumulator();
        long excludedNonPositiveDeltaCount = 0;

        var playerTargetDistance = new NumericAccumulator();
        var score = new OrderedAccumulator();
        var speed = new OrderedAccumulator();
        var progress = new OrderedAccumulator();
        var observedTime = new TimeRangeAccumulator();

        Point previousGaze = null;
        Instant previousGazeTime = null;
        long previousBatchSequence = -1;
        long gazeCount = 0;
        long gameplayCount = 0;

        for (var admitted : batches) {
            var batch = admitted.batch();
            if (previousBatchSequence >= 0 && !isNext(previousBatchSequence, batch.sequence())) {
                previousGaze = null;
                previousGazeTime = null;
            }

            for (var sample : batch.gazeSamples()) {
                var sampleTime = sampleTime(batch.capturedAt(), sample.offsetMs());
                observedTime.add(sampleTime);
                confidence.add(sample.confidence());
                confidenceBins[bin(sample.confidence(), PROFILE.confidenceBinCount())]++;
                int cell = cell(sample.x(), sample.y());
                heatmap[cell]++;
                occupiedCells.add(cell);
                boundingBox.add(sample.x(), sample.y());
                gazeCount++;

                var current = new Point(sample.x(), sample.y());
                if (previousGazeTime != null && sampleTime.isBefore(previousGazeTime)) {
                    previousGaze = null;
                    previousGazeTime = null;
                }
                if (previousGaze != null) {
                    double distance = previousGaze.distanceTo(current);
                    pathDistance.add(distance);
                    double elapsedSeconds = secondsBetween(previousGazeTime, sampleTime);
                    if (elapsedSeconds > 0) velocity.add(distance / elapsedSeconds);
                    else excludedNonPositiveDeltaCount++;
                }
                previousGaze = current;
                previousGazeTime = sampleTime;
            }

            for (var sample : batch.gameplaySamples()) {
                observedTime.add(sampleTime(batch.capturedAt(), sample.offsetMs()));
                playerTargetDistance.add(Math.hypot(
                        sample.playerX() - sample.targetX(), sample.playerY() - sample.targetY()));
                score.add(sample.score());
                speed.add(sample.speed());
                progress.add(sample.progress());
                gameplayCount++;
            }
            previousBatchSequence = batch.sequence();
        }

        long minimumSequence = batches.getFirst().batch().sequence();
        long maximumSequence = batches.getLast().batch().sequence();
        return new BaselineStreamSummary(
                PROFILE,
                first.schemaVersion(),
                first.therapySessionId(),
                first.streamId(),
                minimumSequence,
                maximumSequence,
                missingRanges(batches),
                batches.stream().filter(batch -> batch.admissionQuality() == TelemetryAdmissionQuality.LATE_ARRIVAL)
                        .count(),
                true,
                observedTime.result(),
                new BaselineStreamSummary.SampleCounts(gazeCount, gameplayCount),
                confidence.result(),
                boxed(confidenceBins),
                new BaselineStreamSummary.Heatmap(PROFILE.gridRows(), PROFILE.gridColumns(), boxed(heatmap)),
                boundingBox.result(occupiedCells.size()),
                new BaselineStreamSummary.PathMetrics(
                        pathDistance.value(), velocity.result(), excludedNonPositiveDeltaCount),
                new BaselineStreamSummary.GameplayMetrics(
                        playerTargetDistance.result(), score.result(), speed.result(), progress.result()));
    }

    private void validateBatches(List<AdmittedTelemetryBatch> batches, int schemaVersion,
            UUID therapySessionId, UUID streamId) {
        Set<UUID> batchIds = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (var admitted : batches) {
            Objects.requireNonNull(admitted.admissionQuality(), "admission quality");
            var batch = admitted.batch();
            validateIdentity(batch);
            require(batch.schemaVersion() == schemaVersion, "Mixed schema versions");
            require(batch.therapySessionId().equals(therapySessionId), "Mixed therapy sessions");
            require(batch.streamId().equals(streamId), "Mixed streams");
            require(batchIds.add(batch.batchId()), "Duplicate batchId");
            require(sequences.add(batch.sequence()), "Duplicate sequence");
            validateSamples(batch);
        }
    }

    private void validateIdentity(AcceptedTelemetryBatch batch) {
        Objects.requireNonNull(batch, "batch");
        require(batch.schemaVersion() == 1, "Unsupported schema version");
        require(batch.sequence() >= 0, "Negative sequence");
        Objects.requireNonNull(batch.eventId(), "eventId");
        Objects.requireNonNull(batch.batchId(), "batchId");
        Objects.requireNonNull(batch.therapySessionId(), "therapySessionId");
        Objects.requireNonNull(batch.streamId(), "streamId");
        Objects.requireNonNull(batch.capturedAt(), "capturedAt");
        Objects.requireNonNull(batch.sentAt(), "sentAt");
        Objects.requireNonNull(batch.serverReceivedAt(), "serverReceivedAt");
        Objects.requireNonNull(batch.correlationId(), "correlationId");
        Objects.requireNonNull(batch.gazeSamples(), "gazeSamples");
        Objects.requireNonNull(batch.gameplaySamples(), "gameplaySamples");
        require(!batch.capturedAt().isAfter(batch.sentAt()), "capturedAt exceeds sentAt");
    }

    private void validateSamples(AcceptedTelemetryBatch batch) {
        require((long) batch.gazeSamples().size() + batch.gameplaySamples().size() > 0,
                "Batch contains no samples");
        long previousOffset = -1;
        for (var sample : batch.gazeSamples()) {
            Objects.requireNonNull(sample, "gaze sample");
            require(sample.offsetMs() >= previousOffset && sample.offsetMs() >= 0, "Invalid gaze offset");
            requireNormalized(sample.x(), "gaze x");
            requireNormalized(sample.y(), "gaze y");
            requireNormalized(sample.confidence(), "gaze confidence");
            sampleTime(batch.capturedAt(), sample.offsetMs());
            previousOffset = sample.offsetMs();
        }
        previousOffset = -1;
        for (var sample : batch.gameplaySamples()) {
            Objects.requireNonNull(sample, "gameplay sample");
            require(sample.offsetMs() >= previousOffset && sample.offsetMs() >= 0, "Invalid gameplay offset");
            requireNormalized(sample.playerX(), "player x");
            requireNormalized(sample.playerY(), "player y");
            requireNormalized(sample.targetX(), "target x");
            requireNormalized(sample.targetY(), "target y");
            require(sample.score() >= 0, "Negative gameplay score");
            requireFinite(sample.speed(), "gameplay speed");
            require(sample.speed() >= 0, "Negative gameplay speed");
            requireNormalized(sample.progress(), "gameplay progress");
            sampleTime(batch.capturedAt(), sample.offsetMs());
            previousOffset = sample.offsetMs();
        }
    }

    private List<BaselineStreamSummary.SequenceRange> missingRanges(List<AdmittedTelemetryBatch> batches) {
        var missing = new ArrayList<BaselineStreamSummary.SequenceRange>();
        long previous = batches.getFirst().batch().sequence();
        for (int index = 1; index < batches.size(); index++) {
            long current = batches.get(index).batch().sequence();
            if (!isNext(previous, current)) {
                missing.add(new BaselineStreamSummary.SequenceRange(previous + 1, current - 1));
            }
            previous = current;
        }
        return List.copyOf(missing);
    }

    private boolean isNext(long previous, long current) {
        return previous != Long.MAX_VALUE && current == previous + 1;
    }

    private int cell(double x, double y) {
        int column = bin(x, PROFILE.gridColumns());
        int row = bin(y, PROFILE.gridRows());
        return row * PROFILE.gridColumns() + column;
    }

    private int bin(double value, int count) {
        return Math.min((int) Math.floor(value * count), count - 1);
    }

    private Instant sampleTime(Instant capturedAt, long offsetMs) {
        try {
            return capturedAt.plusMillis(offsetMs);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid sample time", invalid);
        }
    }

    private double secondsBetween(Instant start, Instant end) {
        var duration = Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private List<Long> boxed(long[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    private void requireNormalized(double value, String name) {
        requireFinite(value, name);
        require(value >= 0 && value <= 1, name + " is outside normalized range");
    }

    private void requireFinite(double value, String name) {
        require(Double.isFinite(value), name + " must be finite");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class CompensatedSum {
        private double sum;
        private double compensation;

        void add(double value) {
            double tentative = sum + value;
            compensation += Math.abs(sum) >= Math.abs(value)
                    ? (sum - tentative) + value
                    : (value - tentative) + sum;
            sum = tentative;
        }

        double value() {
            return sum + compensation;
        }
    }

    private static final class NumericAccumulator {
        private final CompensatedSum sum = new CompensatedSum();
        private long count;
        private double minimum = Double.POSITIVE_INFINITY;
        private double maximum = Double.NEGATIVE_INFINITY;

        void add(double value) {
            count++;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            sum.add(value);
        }

        BaselineStreamSummary.NumericSummary result() {
            if (count == 0) return BaselineStreamSummary.NumericSummary.empty();
            double mean = Math.max(minimum, Math.min(maximum, sum.value() / count));
            return new BaselineStreamSummary.NumericSummary(count, OptionalDouble.of(minimum),
                    OptionalDouble.of(maximum), OptionalDouble.of(mean));
        }
    }

    private static final class OrderedAccumulator {
        private final NumericAccumulator distribution = new NumericAccumulator();
        private double first;
        private double last;

        void add(double value) {
            if (distribution.count == 0) first = value;
            last = value;
            distribution.add(value);
        }

        BaselineStreamSummary.OrderedNumericSummary result() {
            if (distribution.count == 0) return BaselineStreamSummary.OrderedNumericSummary.empty();
            return new BaselineStreamSummary.OrderedNumericSummary(distribution.result(),
                    OptionalDouble.of(first), OptionalDouble.of(last), OptionalDouble.of(last - first));
        }
    }

    private static final class BoundingBoxAccumulator {
        private double minimumX = Double.POSITIVE_INFINITY;
        private double minimumY = Double.POSITIVE_INFINITY;
        private double maximumX = Double.NEGATIVE_INFINITY;
        private double maximumY = Double.NEGATIVE_INFINITY;

        void add(double x, double y) {
            minimumX = Math.min(minimumX, x);
            minimumY = Math.min(minimumY, y);
            maximumX = Math.max(maximumX, x);
            maximumY = Math.max(maximumY, y);
        }

        BaselineStreamSummary.Coverage result(int occupiedCellCount) {
            if (occupiedCellCount == 0) return new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0);
            double width = maximumX - minimumX;
            double height = maximumY - minimumY;
            var box = new BaselineStreamSummary.BoundingBox(
                    minimumX, minimumY, maximumX, maximumY, width, height, width * height);
            double ratio = occupiedCellCount / (double) (PROFILE.gridRows() * PROFILE.gridColumns());
            return new BaselineStreamSummary.Coverage(Optional.of(box), occupiedCellCount, ratio);
        }
    }

    private static final class TimeRangeAccumulator {
        private Instant minimum;
        private Instant maximum;

        void add(Instant value) {
            if (minimum == null || value.isBefore(minimum)) minimum = value;
            if (maximum == null || value.isAfter(maximum)) maximum = value;
        }

        Optional<BaselineStreamSummary.TimeRange> result() {
            return minimum == null ? Optional.empty()
                    : Optional.of(new BaselineStreamSummary.TimeRange(minimum, maximum));
        }
    }

    private record Point(double x, double y) {
        double distanceTo(Point other) {
            return Math.hypot(other.x - x, other.y - y);
        }
    }
}
