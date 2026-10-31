package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import java.time.Instant;
import java.util.*;

/** Strict local wire translation; production has no dependency on Analytics implementation types. */
final class ReportAnalyticsDecoder {
    // Even minimal full-range JSON cannot fit this many entries into the transport's 1 MiB body.
    private static final int MAX_FULL_GAPS = 32768;
    private ReportAnalyticsDecoder() {}
    record Item(UUID streamId, String calculationVersion, long revision, Counts counts) {}
    record Discovery(boolean hasNext, List<Item> items) {}

    static Discovery discovery(UUID session, JsonNode node) {
        require(session.equals(uuid(node,"therapySessionId")) && integer(node,"page")==0 && integer(node,"size")==20);
        boolean hasNext = bool(node,"hasNext");
        var items = array(node,"items",20);
        require(!hasNext || items.size()==20);
        var result = new ArrayList<Item>();
        String previous = null;
        for (var item : items) {
            UUID id = uuid(item,"streamId");
            require(previous==null || previous.compareTo(id.toString())<0);
            String version = text(item,"calculationVersion",128);
            long revision = number(item,"revision");
            require("baseline-v1".equals(version) && revision>0);
            result.add(new Item(id,version,revision,counts(field(item,"sampleCounts"))));
            previous = id.toString();
        }
        return new Discovery(hasNext,List.copyOf(result));
    }

    static Summary summary(UUID session, Item discovered, JsonNode node) {
        var source = source(node,"schemaVersion");
        var completeness = completeness(field(node,"completeness"));
        require(source.therapySessionId().equals(session) && source.streamId().equals(discovered.streamId())
                && source.calculationVersion().equals(discovered.calculationVersion()) && source.revision()==discovered.revision()
                && completeness.sampleCounts().equals(discovered.counts()));
        var metrics = field(node,"metrics");
        var coverage = field(metrics,"coverage");
        var path = field(metrics,"gazePath");
        var gameplay = field(metrics,"gameplay");
        return new Summary(source,completeness,numeric(field(metrics,"confidence")),longs(metrics,"confidenceHistogram",10),
                grid(field(metrics,"heatmap")),new Coverage(box(field(coverage,"boundingBox")),
                number(coverage,"occupiedCellCount"),decimal(coverage,"occupiedCellRatio")),
                new Path(decimal(path,"totalDistance"),numeric(field(path,"velocity")),number(path,"excludedNonPositiveDeltaCount")),
                new Gameplay(numeric(field(gameplay,"playerTargetDistance")),ordered(field(gameplay,"score")),
                        ordered(field(gameplay,"speed")),ordered(field(gameplay,"progress"))));
    }

    static Stream stream(Summary summary, JsonNode heatmap, JsonNode gaze) {
        var complete = summary.completeness();
        require(summary.source().equals(source(heatmap,"contractVersion")));
        require(number(heatmap,"minimumSequence")==complete.minimumSequence()
                && number(heatmap,"maximumSequence")==complete.maximumSequence()
                && number(heatmap,"highSequence")==complete.highSequence()
                && number(heatmap,"gazeSampleCount")==complete.sampleCounts().gaze());
        overlapping(heatmap,complete);
        var heatmapGaps = fullGaps(heatmap,complete);
        var grid = grid(heatmap);
        require(grid.equals(summary.heatmap()));
        require("normalized-0..1".equals(text(heatmap,"coordinateSpace",64))
                && "raw-gaze-sample-count".equals(text(heatmap,"cellWeight",64)) && !bool(heatmap,"confidenceFiltered"));
        var translatedHeatmap = new Heatmap(summary.source(),complete,grid,"NORMALIZED_ZERO_TO_ONE","RAW_GAZE_SAMPLE_COUNT",false);

        require(integer(gaze,"contractVersion")==summary.source().contractVersion()
                && "OBSERVED_GAZE_SAMPLES".equals(text(gaze,"pointSemantics",64))
                && text(gaze,"calculationVersion",128).equals(summary.source().calculationVersion())
                && number(gaze,"projectionRevision")==summary.source().revision());
        var gazeCompleteness = field(gaze,"completeness");
        require(integer(gazeCompleteness,"sourceSchemaVersion")==summary.source().sourceSchemaVersion()
                && number(gazeCompleteness,"minimumSequence")==complete.minimumSequence()
                && number(gazeCompleteness,"highSequence")==complete.maximumSequence()
                && number(gazeCompleteness,"observedGazePointCount")==complete.sampleCounts().gaze());
        overlapping(gazeCompleteness,complete);
        require(heatmapGaps.equals(fullGaps(gazeCompleteness,complete)));
        var cursor = field(gaze,"nextCursor");
        boolean hasNext = !cursor.isNull();
        if (hasNext) require(cursor.isTextual() && !cursor.textValue().isBlank() && cursor.textValue().length()<=1024);
        var points = new ArrayList<GazePoint>();
        for (var point : array(gaze,"points",100)) {
            long sequence = number(point,"sequence");
            require(heatmapGaps.stream().noneMatch(g -> sequence>=g.fromInclusive() && sequence<=g.toInclusive()));
            points.add(new GazePoint(sequence,integer(point,"gazeSampleIndex"),instant(field(point,"effectiveTime")),
                    decimal(point,"x"),decimal(point,"y"),decimal(point,"confidence"),bool(point,"segmentBreakBefore")));
        }
        // Gaze V1 omits identity and selection fields: only this adapter's fixed initial request
        // establishes session/stream, page size and startsAtBeginning. Scan limit 64 is the server V1 contract.
        return new Stream(summary,translatedHeatmap,new GazePage(summary.source(),complete,100,64,true,hasNext,points));
    }

    private static Source source(JsonNode node,String contractField) {
        return new Source(uuid(node,"therapySessionId"),uuid(node,"streamId"),text(node,"calculationVersion",128),
                number(node,"revision"),integer(node,contractField),integer(node,"sourceSchemaVersion"));
    }
    private static Completeness completeness(JsonNode node) {
        var ranges = ranges(node,"missingSequenceRanges",1000);
        long count = number(node,"missingSequenceRangeCount");
        require(count>=0 && ranges.size()==Math.min(count,1000));
        return new Completeness(number(node,"minimumSequence"),number(node,"maximumSequence"),number(node,"highSequence"),
                count,bool(node,"missingSequenceRangesTruncated"),ranges,number(node,"lateArrivalCount"),
                bool(node,"unknownSessionBoundary"),time(field(node,"observedTimeRange")),counts(field(node,"sampleCounts")));
    }
    private static void overlapping(JsonNode node,Completeness expected) {
        require(number(node,"lateArrivalCount")==expected.lateArrivalCount()
                && bool(node,"unknownSessionBoundary")==expected.unknownSessionBoundary()
                && Objects.equals(time(field(node,"observedTimeRange")),expected.observedTimeRange()));
    }
    private static List<Range> fullGaps(JsonNode node,Completeness expected) {
        var ranges = ranges(node,"missingSequenceRanges",MAX_FULL_GAPS);
        require(ranges.size()==expected.missingSequenceRangeCount()
                && ranges.subList(0,Math.min(1000,ranges.size())).equals(expected.missingSequenceRanges()));
        long previous = expected.minimumSequence();
        for (var range : ranges) {
            require(range.fromInclusive()>previous && range.toInclusive()<expected.maximumSequence());
            previous = range.toInclusive();
        }
        return ranges;
    }
    private static List<Range> ranges(JsonNode node,String key,int maximum) {
        var result = new ArrayList<Range>();
        for (var range : array(node,key,maximum)) result.add(new Range(number(range,"fromInclusive"),number(range,"toInclusive")));
        return List.copyOf(result);
    }
    private static Counts counts(JsonNode node) { return new Counts(number(node,"gaze"),number(node,"gameplay"),number(node,"total")); }
    private static Grid grid(JsonNode node) { return new Grid(integer(node,"rows"),integer(node,"columns"),longs(node,"cellCounts",100)); }
    private static Numeric numeric(JsonNode node) {
        return new Numeric(number(node,"count"),nullableDecimal(node,"minimum"),nullableDecimal(node,"maximum"),nullableDecimal(node,"mean"));
    }
    private static Ordered ordered(JsonNode node) {
        return new Ordered(numeric(field(node,"distribution")),nullableDecimal(node,"first"),nullableDecimal(node,"last"),nullableDecimal(node,"signedDelta"));
    }
    private static BoundingBox box(JsonNode node) {
        if (node.isNull()) return null;
        return new BoundingBox(decimal(node,"minimumX"),decimal(node,"minimumY"),decimal(node,"maximumX"),decimal(node,"maximumY"),
                decimal(node,"width"),decimal(node,"height"),decimal(node,"area"));
    }
    private static TimeRange time(JsonNode node) {
        return node.isNull() ? null : new TimeRange(instant(field(node,"minimum")),instant(field(node,"maximum")));
    }
    private static Instant instant(JsonNode node) {
        require(node.isTextual() && node.textValue().length()<=80); return Instant.parse(node.textValue());
    }
    private static List<Long> longs(JsonNode node,String key,int maximum) {
        var result = new ArrayList<Long>(); for (var value : array(node,key,maximum)) result.add(longValue(value)); return List.copyOf(result);
    }
    private static JsonNode field(JsonNode node,String key) {
        require(node!=null && node.isObject() && node.has(key)); return node.get(key);
    }
    private static JsonNode array(JsonNode node,String key,int maximum) {
        var value = field(node,key); require(value.isArray() && value.size()<=maximum); return value;
    }
    private static String text(JsonNode node,String key,int maximum) {
        var value=field(node,key); require(value.isTextual() && !value.textValue().isBlank() && value.textValue().length()<=maximum); return value.textValue();
    }
    private static UUID uuid(JsonNode node,String key) {
        String value=text(node,key,36); UUID id=UUID.fromString(value); require(id.toString().equals(value)); return id;
    }
    private static long number(JsonNode node,String key) { return longValue(field(node,key)); }
    private static long longValue(JsonNode node) { require(node.isIntegralNumber() && node.canConvertToLong()); return node.longValue(); }
    private static int integer(JsonNode node,String key) { return Math.toIntExact(number(node,key)); }
    private static boolean bool(JsonNode node,String key) { var value=field(node,key); require(value.isBoolean()); return value.booleanValue(); }
    private static double decimal(JsonNode node,String key) { var value=field(node,key); require(value.isNumber() && Double.isFinite(value.doubleValue())); return value.doubleValue(); }
    private static Double nullableDecimal(JsonNode node,String key) { return field(node,key).isNull() ? null : decimal(node,key); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid report analytics response"); }
}
