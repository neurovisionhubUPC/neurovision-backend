package com.neurovision.rehab.clinical.application.ports.out.integration;

import java.time.Instant;
import java.util.*;

/** Clinical-owned translation of existing baseline-v1 observations; no cross-service Java types.
 * An adapter must authorize every source read using the original token. Errors, denials and
 * incomplete lookups throw; they must never be translated to ConfirmedNoEvidence.
 */
public interface SessionReportAnalyticsPort {
    Observation observe(UUID therapySessionId, String originalAccessToken);

    sealed interface Observation permits ConfirmedNoEvidence, ObservedEvidence {
        UUID therapySessionId();
    }
    /** Proof: successful authorized first discovery page with zero supported observed projections.
     * Not an empty later page, partial lookup, unsupported/corrupt projection, or dependency failure.
     */
    record ConfirmedNoEvidence(UUID therapySessionId) implements Observation {
        public ConfirmedNoEvidence { Objects.requireNonNull(therapySessionId); }
        @Override public String toString() { return "ConfirmedNoEvidence[redacted]"; }
    }
    /** One bounded discovery page; hasNext explicitly limits the report's observed stream selection. */
    record ObservedEvidence(UUID therapySessionId, int discoveryPage, int discoverySize,
            boolean hasNext, List<Stream> streams) implements Observation {
        public ObservedEvidence {
            Objects.requireNonNull(therapySessionId);
            streams = bounded(streams, 50);
            require(discoveryPage >= 0 && discoverySize >= 1 && discoverySize <= 50
                    && !streams.isEmpty() && streams.size() <= discoverySize);
            var ids = new HashSet<UUID>();
            for (var stream : streams) {
                require(therapySessionId.equals(stream.summary().source().therapySessionId()));
                require(ids.add(stream.summary().source().streamId()));
            }
        }
        @Override public String toString() { return "ObservedEvidence[redacted]"; }
    }
    record Source(UUID therapySessionId, UUID streamId, String calculationVersion,
            long revision, int contractVersion, int sourceSchemaVersion) {
        public Source {
            Objects.requireNonNull(therapySessionId); Objects.requireNonNull(streamId);
            require("baseline-v1".equals(calculationVersion) && revision > 0
                    && contractVersion == 1 && sourceSchemaVersion == 1);
        }
        @Override public String toString() { return "AnalyticsSource[redacted]"; }
    }
    record Range(long fromInclusive, long toInclusive) {
        public Range { require(fromInclusive >= 0 && toInclusive >= fromInclusive); }
    }
    record TimeRange(Instant minimum, Instant maximum) {
        public TimeRange { Objects.requireNonNull(minimum); Objects.requireNonNull(maximum); require(!maximum.isBefore(minimum)); }
    }
    record Counts(long gaze, long gameplay, long total) {
        public Counts { require(gaze >= 0 && gameplay >= 0 && total == Math.addExact(gaze, gameplay)); }
    }
    record Completeness(long minimumSequence, long maximumSequence, long highSequence,
            long missingSequenceRangeCount, boolean missingSequenceRangesTruncated, List<Range> missingSequenceRanges,
            long lateArrivalCount, boolean unknownSessionBoundary, TimeRange observedTimeRange, Counts sampleCounts) {
        public Completeness {
            Objects.requireNonNull(sampleCounts);
            missingSequenceRanges = bounded(missingSequenceRanges, 1000);
            require(minimumSequence >= 0 && maximumSequence >= minimumSequence && highSequence == maximumSequence
                    && lateArrivalCount >= 0 && unknownSessionBoundary
                    && missingSequenceRangeCount >= missingSequenceRanges.size()
                    && missingSequenceRangesTruncated == (missingSequenceRangeCount > missingSequenceRanges.size())
                    && (sampleCounts.total() == 0) == (observedTimeRange == null));
            long previous = minimumSequence;
            for (var gap : missingSequenceRanges) {
                require(gap.fromInclusive() > previous && gap.toInclusive() < maximumSequence);
                previous = gap.toInclusive();
            }
        }
    }
    record Numeric(long count, Double minimum, Double maximum, Double mean) {
        public Numeric {
            require(count >= 0);
            if (count == 0) require(minimum == null && maximum == null && mean == null);
            else require(finite(minimum) && finite(maximum) && finite(mean) && minimum <= mean && mean <= maximum);
        }
    }
    record Ordered(Numeric distribution, Double first, Double last, Double signedDelta) {
        public Ordered {
            Objects.requireNonNull(distribution);
            if (distribution.count() == 0) require(first == null && last == null && signedDelta == null);
            else require(finite(first) && finite(last) && finite(signedDelta)
                    && first >= distribution.minimum() && first <= distribution.maximum()
                    && last >= distribution.minimum() && last <= distribution.maximum());
        }
    }
    record Grid(int rows, int columns, List<Long> cellCounts) {
        public Grid {
            cellCounts = bounded(cellCounts, 100);
            require(rows == 10 && columns == 10 && cellCounts.size() == 100);
            sum(cellCounts);
        }
    }
    record BoundingBox(double minimumX, double minimumY, double maximumX, double maximumY,
            double width, double height, double area) {
        public BoundingBox {
            require(normalized(minimumX) && normalized(minimumY) && normalized(maximumX) && normalized(maximumY)
                    && minimumX <= maximumX && minimumY <= maximumY && normalized(width) && normalized(height) && normalized(area));
        }
        @Override public String toString() { return "BoundingBox[redacted]"; }
    }
    record Coverage(BoundingBox boundingBox, long occupiedCellCount, double occupiedCellRatio) {
        public Coverage {
            require(occupiedCellCount >= 0 && occupiedCellCount <= 100 && normalized(occupiedCellRatio)
                    && (occupiedCellCount == 0) == (boundingBox == null));
        }
    }
    record Path(double totalDistance, Numeric velocity, long excludedNonPositiveDeltaCount) {
        public Path {
            Objects.requireNonNull(velocity);
            require(Double.isFinite(totalDistance) && totalDistance >= 0 && excludedNonPositiveDeltaCount >= 0
                    && (velocity.count() == 0 || velocity.minimum() >= 0));
        }
    }
    record Gameplay(Numeric playerTargetDistance, Ordered score, Ordered speed, Ordered progress) {
        public Gameplay {
            Objects.requireNonNull(playerTargetDistance); Objects.requireNonNull(score);
            Objects.requireNonNull(speed); Objects.requireNonNull(progress);
        }
    }
    record Summary(Source source, Completeness completeness, Numeric confidence,
            List<Long> confidenceHistogram, Grid heatmap, Coverage coverage, Path gazePath, Gameplay gameplay) {
        public Summary {
            Objects.requireNonNull(source); Objects.requireNonNull(completeness); Objects.requireNonNull(confidence);
            Objects.requireNonNull(heatmap); Objects.requireNonNull(coverage); Objects.requireNonNull(gazePath); Objects.requireNonNull(gameplay);
            confidenceHistogram = bounded(confidenceHistogram, 10);
            var counts = completeness.sampleCounts();
            require(confidenceHistogram.size() == 10 && sum(confidenceHistogram) == counts.gaze()
                    && confidence.count() == counts.gaze() && sum(heatmap.cellCounts()) == counts.gaze()
                    && (counts.gaze() == 0 || (normalized(confidence.minimum()) && normalized(confidence.maximum())))
                    && gameplay.playerTargetDistance().count() == counts.gameplay()
                    && gameplay.score().distribution().count() == counts.gameplay()
                    && gameplay.speed().distribution().count() == counts.gameplay()
                    && gameplay.progress().distribution().count() == counts.gameplay());
            long occupied = heatmap.cellCounts().stream().filter(value -> value > 0).count();
            require(coverage.occupiedCellCount() == occupied && coverage.occupiedCellRatio() == occupied / 100.0);
        }
        @Override public String toString() { return "AnalyticsSummary[redacted]"; }
    }
    record Heatmap(Source source, Completeness completeness, Grid grid, String coordinateSpace,
            String cellWeight, boolean confidenceFiltered) {
        public Heatmap {
            Objects.requireNonNull(source); Objects.requireNonNull(completeness); Objects.requireNonNull(grid);
            require("NORMALIZED_ZERO_TO_ONE".equals(coordinateSpace) && "RAW_GAZE_SAMPLE_COUNT".equals(cellWeight)
                    && !confidenceFiltered && sum(grid.cellCounts()) == completeness.sampleCounts().gaze());
        }
        @Override public String toString() { return "Heatmap[redacted]"; }
    }
    record GazePoint(long sequence, int gazeSampleIndex, Instant effectiveTime, double x, double y,
            double confidence, boolean segmentBreakBefore) {
        public GazePoint {
            Objects.requireNonNull(effectiveTime);
            require(sequence >= 0 && gazeSampleIndex >= 0 && normalized(x) && normalized(y) && normalized(confidence));
        }
        @Override public String toString() { return "GazePoint[redacted]"; }
    }
    /** Page/window metadata describes selection, never whole-session completeness or physiological fixations. */
    record GazePage(Source source, Completeness completeness, int pageSize, int sourceBatchScanLimit,
            boolean startsAtBeginning, boolean hasNext, List<GazePoint> points) {
        public GazePage {
            Objects.requireNonNull(source); Objects.requireNonNull(completeness);
            points = bounded(points, 500);
            require(pageSize >= 1 && pageSize <= 500 && points.size() <= pageSize && sourceBatchScanLimit == 64
                    && points.size() <= completeness.sampleCounts().gaze());
            GazePoint previous = null;
            for (var point : points) {
                require(point.sequence() >= completeness.minimumSequence() && point.sequence() <= completeness.maximumSequence()
                        && completeness.missingSequenceRanges().stream().noneMatch(g -> point.sequence() >= g.fromInclusive() && point.sequence() <= g.toInclusive())
                        && completeness.observedTimeRange() != null
                        && !point.effectiveTime().isBefore(completeness.observedTimeRange().minimum())
                        && !point.effectiveTime().isAfter(completeness.observedTimeRange().maximum()));
                if (previous != null) require(point.sequence() > previous.sequence()
                        || (point.sequence() == previous.sequence() && point.gazeSampleIndex() > previous.gazeSampleIndex()));
                previous = point;
            }
            if (startsAtBeginning && !hasNext) require(points.size() == completeness.sampleCounts().gaze());
        }
        @Override public String toString() { return "GazePage[redacted]"; }
    }
    record Stream(Summary summary, Heatmap heatmap, GazePage gazePlot) {
        public Stream {
            Objects.requireNonNull(summary); Objects.requireNonNull(heatmap); Objects.requireNonNull(gazePlot);
            require(summary.source().equals(heatmap.source()) && summary.source().equals(gazePlot.source())
                    && summary.completeness().equals(heatmap.completeness()) && summary.completeness().equals(gazePlot.completeness())
                    && summary.heatmap().equals(heatmap.grid()));
        }
        @Override public String toString() { return "AnalyticsStream[redacted]"; }
    }
    private static <T> List<T> bounded(List<T> values, int maximum) {
        require(values != null && values.size() <= maximum);
        return List.copyOf(values);
    }
    private static long sum(List<Long> values) {
        long total = 0;
        for (var value : values) { require(value != null && value >= 0); total = Math.addExact(total, value); }
        return total;
    }
    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static boolean normalized(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid report analytics observation");
    }
}
