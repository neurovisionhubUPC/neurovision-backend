package com.neurovision.rehab.clinical.infrastructure.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportAnalyticsDecoderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID STREAM = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String TIME = "2026-01-01T00:00:00Z";

    @Test void translatesPublishedShapesWithoutInventingWireMetadata() {
        var result = decode(summary(), heatmap(), gaze());
        assertEquals(1, result.summary().completeness().sampleCounts().gaze());
        assertEquals(result.summary().completeness(), result.heatmap().completeness());
        assertEquals("NORMALIZED_ZERO_TO_ONE", result.heatmap().coordinateSpace());
        assertEquals("RAW_GAZE_SAMPLE_COUNT", result.heatmap().cellWeight());
        assertEquals(100, result.gazePlot().pageSize());
        assertEquals(64, result.gazePlot().sourceBatchScanLimit());
        assertTrue(result.gazePlot().startsAtBeginning());
        assertFalse(result.gazePlot().hasNext());
        assertEquals(1, result.gazePlot().points().size());
    }

    @Test void checksDiscoveryIdentityOrderingVersionsCountsAndWindow() {
        for (String field : List.of("therapySessionId", "page", "size", "hasNext", "items")) {
            var broken = discovery(); broken.remove(field);
            assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, broken), field);
        }
        var duplicate = discovery(); duplicate.withArray("items").add(duplicate.withArray("items").get(0).deepCopy());
        assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, duplicate));
        var reversed = discovery();
        reversed.withArray("items").add(item(UUID.fromString("00000000-0000-0000-0000-000000000000")));
        assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, reversed));
        for (String field : List.of("calculationVersion", "revision", "streamId", "sampleCounts")) {
            var broken = discovery(); ((ObjectNode)broken.withArray("items").get(0)).remove(field);
            assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, broken), field);
        }
        var unknown = discovery(); ((ObjectNode)unknown.withArray("items").get(0)).put("calculationVersion", "future-v2");
        assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, unknown));
        var inconsistent = discovery(); inconsistent.put("hasNext", true);
        assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, inconsistent));
        var wrongWindow = discovery(); wrongWindow.put("page", 1);
        assertThrows(RuntimeException.class, () -> ReportAnalyticsDecoder.discovery(SESSION, wrongWindow));
    }

    @Test void rejectsEachIndependentlyOverlappingHeatmapField() {
        for (String field : List.of("contractVersion", "sourceSchemaVersion", "therapySessionId", "streamId",
                "calculationVersion", "revision", "rows", "columns", "cellCounts", "coordinateSpace", "cellWeight",
                "confidenceFiltered", "minimumSequence", "maximumSequence", "highSequence", "missingSequenceRanges",
                "lateArrivalCount", "unknownSessionBoundary", "gazeSampleCount", "observedTimeRange")) {
            var broken = heatmap(); broken.remove(field);
            assertThrows(RuntimeException.class, () -> decode(summary(), broken, gaze()), field);
        }
        var revision = heatmap(); revision.put("revision", 2);
        assertThrows(RuntimeException.class, () -> decode(summary(), revision, gaze()));
        var count = heatmap(); count.put("gazeSampleCount", 2);
        assertThrows(RuntimeException.class, () -> decode(summary(), count, gaze()));
        var time = heatmap(); time.withObject("observedTimeRange").put("maximum", "2026-01-01T00:00:01Z");
        assertThrows(RuntimeException.class, () -> decode(summary(), time, gaze()));
    }

    @Test void rejectsEachOverlappingGazeFieldAndWrongSemantics() {
        for (String field : List.of("contractVersion", "pointSemantics", "calculationVersion", "projectionRevision", "completeness", "points", "nextCursor")) {
            var broken = gaze(); broken.remove(field);
            assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), broken), field);
        }
        for (String field : List.of("sourceSchemaVersion", "minimumSequence", "highSequence", "lateArrivalCount",
                "unknownSessionBoundary", "observedGazePointCount", "missingSequenceRanges", "observedTimeRange")) {
            var broken = gaze(); broken.withObject("completeness").remove(field);
            assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), broken), field);
        }
        var semantics = gaze(); semantics.put("pointSemantics", "FIXATIONS");
        assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), semantics));
        var revision = gaze(); revision.put("projectionRevision", 2);
        assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), revision));
        for (String cursor : List.of("", " ", "a".repeat(1025))) {
            var broken = gaze(); broken.put("nextCursor", cursor);
            assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), broken));
        }
    }

    @Test void preservesEmptyPartialObservedWindowAndRejectsUnorderedOrExcessPoints() {
        var partial = gaze(); partial.withArray("points").removeAll(); partial.put("nextCursor", "opaque-window");
        assertTrue(decode(summary(), heatmap(), partial).gazePlot().hasNext());
        assertTrue(decode(summary(), heatmap(), partial).gazePlot().points().isEmpty());
        var duplicate = gaze(); duplicate.withArray("points").add(duplicate.withArray("points").get(0).deepCopy());
        assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), duplicate));
        var invalid = gaze(); ((ObjectNode)invalid.withArray("points").get(0)).put("x", 1.01);
        assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), invalid));
        var over = gaze(); for (int i=0; i<100; i++) over.withArray("points").add(over.withArray("points").get(0).deepCopy());
        assertThrows(RuntimeException.class, () -> decode(summary(), heatmap(), over));
    }

    @Test void validatesFullGapTailsBeforeKeepingTheSummaryPreview() {
        var summary = summary(); var heatmap = heatmap(); var gaze = gaze();
        summary.withObject("completeness").put("maximumSequence", 2004).put("highSequence", 2004)
                .put("missingSequenceRangeCount", 1001).put("missingSequenceRangesTruncated", true);
        heatmap.put("maximumSequence", 2004).put("highSequence", 2004);
        gaze.withObject("completeness").put("highSequence", 2004);
        for (int i=0; i<1001; i++) {
            var range=JSON.createObjectNode().put("fromInclusive", 2*i+1).put("toInclusive", 2*i+1);
            if (i<1000) summary.withObject("completeness").withArray("missingSequenceRanges").add(range.deepCopy());
            heatmap.withArray("missingSequenceRanges").add(range.deepCopy());
            gaze.withObject("completeness").withArray("missingSequenceRanges").add(range.deepCopy());
        }
        var translated=decode(summary,heatmap,gaze);
        assertEquals(1000, translated.summary().completeness().missingSequenceRanges().size());
        assertEquals(1001, translated.summary().completeness().missingSequenceRangeCount());
        assertTrue(translated.summary().completeness().missingSequenceRangesTruncated());
        ((ObjectNode)gaze.withObject("completeness").withArray("missingSequenceRanges").get(1000))
                .put("fromInclusive", 2002).put("toInclusive", 2002);
        assertThrows(RuntimeException.class, () -> decode(summary,heatmap,gaze));
        ((ObjectNode)heatmap.withArray("missingSequenceRanges").get(1000)).put("toInclusive", 2004);
        assertThrows(RuntimeException.class, () -> decode(summary,heatmap,gaze));
    }

    @Test void rejectsMissingCoercedUnsupportedAndIncoherentSummary() {
        for (String field : List.of("schemaVersion", "sourceSchemaVersion", "therapySessionId", "streamId",
                "calculationVersion", "revision", "completeness", "metrics")) {
            var broken = summary(); broken.remove(field);
            assertThrows(RuntimeException.class, () -> decode(broken, heatmap(), gaze()), field);
        }
        var coercion = summary(); coercion.put("revision", "1");
        assertThrows(RuntimeException.class, () -> decode(coercion, heatmap(), gaze()));
        var schema = summary(); schema.put("schemaVersion", 2);
        assertThrows(RuntimeException.class, () -> decode(schema, heatmap(), gaze()));
        var counts = summary(); counts.withObject("completeness").withObject("sampleCounts").put("total", 2);
        assertThrows(RuntimeException.class, () -> decode(counts, heatmap(), gaze()));
        var unsupported = summary(); unsupported.put("calculationVersion", "future-v2");
        assertThrows(RuntimeException.class, () -> decode(unsupported, heatmap(), gaze()));
    }

    @Test void rejectsChangedValuesInEveryIndependentCompletenessField() {
        for(String field:List.of("minimumSequence","maximumSequence","highSequence","lateArrivalCount","gazeSampleCount")) {
            var broken=heatmap(); broken.put(field,9);
            assertThrows(RuntimeException.class,()->decode(summary(),broken,gaze()),field);
        }
        for(String field:List.of("minimumSequence","highSequence","lateArrivalCount","observedGazePointCount","sourceSchemaVersion")) {
            var broken=gaze(); broken.withObject("completeness").put(field,9);
            assertThrows(RuntimeException.class,()->decode(summary(),heatmap(),broken),field);
        }
        var boundary=heatmap(); boundary.put("unknownSessionBoundary",false);
        assertThrows(RuntimeException.class,()->decode(summary(),boundary,gaze()));
        var gazeBoundary=gaze(); gazeBoundary.withObject("completeness").put("unknownSessionBoundary",false);
        assertThrows(RuntimeException.class,()->decode(summary(),heatmap(),gazeBoundary));
        var gazeTime=gaze(); gazeTime.withObject("completeness").withObject("observedTimeRange").put("maximum","2026-01-01T00:00:01Z");
        assertThrows(RuntimeException.class,()->decode(summary(),heatmap(),gazeTime));
        var changedGrid=heatmap(); changedGrid.withArray("cellCounts").set(0,JSON.getNodeFactory().numberNode(1));
        assertThrows(RuntimeException.class,()->decode(summary(),changedGrid,gaze()));
        var foreign=heatmap(); foreign.put("streamId",UUID.randomUUID().toString());
        assertThrows(RuntimeException.class,()->decode(summary(),foreign,gaze()));
        var discovered=ReportAnalyticsDecoder.discovery(SESSION,discovery()).items().get(0);
        var countMismatch=summary(); countMismatch.withObject("completeness").withObject("sampleCounts").put("gaze",2).put("total",2);
        assertThrows(RuntimeException.class,()->ReportAnalyticsDecoder.summary(SESSION,discovered,countMismatch));
    }

    static Stream decode(ObjectNode summary, ObjectNode heatmap, ObjectNode gaze) {
        var discovered=ReportAnalyticsDecoder.discovery(SESSION, discovery()).items().get(0);
        return ReportAnalyticsDecoder.stream(ReportAnalyticsDecoder.summary(SESSION,discovered,summary),heatmap,gaze);
    }
    static ObjectNode discovery() {
        var result=JSON.createObjectNode().put("therapySessionId",SESSION.toString()).put("page",0).put("size",20).put("hasNext",false);
        result.putArray("items").add(item(STREAM)); return result;
    }
    static ObjectNode item(UUID stream) {
        var result=JSON.createObjectNode().put("streamId",stream.toString()).put("calculationVersion","baseline-v1").put("revision",1);
        result.putObject("sampleCounts").put("gaze",1).put("gameplay",0).put("total",1); return result;
    }
    static ObjectNode summary() {
        var result=identity().put("schemaVersion",1);
        var completeness=result.putObject("completeness").put("minimumSequence",0).put("maximumSequence",0)
                .put("highSequence",0).put("missingSequenceRangeCount",0).put("missingSequenceRangesTruncated",false)
                .put("lateArrivalCount",0).put("unknownSessionBoundary",true);
        completeness.putArray("missingSequenceRanges"); completeness.set("observedTimeRange",time());
        completeness.putObject("sampleCounts").put("gaze",1).put("gameplay",0).put("total",1);
        var metrics=result.putObject("metrics"); metrics.set("confidence",numeric(1,0.5));
        var histogram=metrics.putArray("confidenceHistogram"); for(int i=0;i<10;i++) histogram.add(i==5?1:0);
        var grid=metrics.putObject("heatmap").put("rows",10).put("columns",10); grid.set("cellCounts",cells());
        var coverage=metrics.putObject("coverage").put("occupiedCellCount",1).put("occupiedCellRatio",0.01);
        coverage.putObject("boundingBox").put("minimumX",0.5).put("minimumY",0.5).put("maximumX",0.5)
                .put("maximumY",0.5).put("width",0).put("height",0).put("area",0);
        metrics.putObject("gazePath").put("totalDistance",0).put("excludedNonPositiveDeltaCount",0).set("velocity",numeric(0,0));
        var gameplay=metrics.putObject("gameplay"); gameplay.set("playerTargetDistance",numeric(0,0));
        for(String field:List.of("score","speed","progress")) {
            var ordered=gameplay.putObject(field).putNull("first").putNull("last").putNull("signedDelta");
            ordered.set("distribution",numeric(0,0));
        }
        return result;
    }
    static ObjectNode heatmap() {
        var result=identity().put("contractVersion",1).put("rows",10).put("columns",10)
                .put("coordinateSpace","normalized-0..1").put("cellWeight","raw-gaze-sample-count")
                .put("confidenceFiltered",false).put("minimumSequence",0).put("maximumSequence",0).put("highSequence",0)
                .put("lateArrivalCount",0).put("unknownSessionBoundary",true).put("gazeSampleCount",1);
        result.set("cellCounts",cells()); result.putArray("missingSequenceRanges"); result.set("observedTimeRange",time()); return result;
    }
    static ObjectNode gaze() {
        var result=JSON.createObjectNode().put("contractVersion",1).put("pointSemantics","OBSERVED_GAZE_SAMPLES")
                .put("calculationVersion","baseline-v1").put("projectionRevision",1).putNull("nextCursor");
        var completeness=result.putObject("completeness").put("sourceSchemaVersion",1).put("minimumSequence",0)
                .put("highSequence",0).put("lateArrivalCount",0).put("unknownSessionBoundary",true).put("observedGazePointCount",1);
        completeness.putArray("missingSequenceRanges"); completeness.set("observedTimeRange",time());
        result.putArray("points").addObject().put("sequence",0).put("gazeSampleIndex",0).put("effectiveTime",TIME)
                .put("x",0.5).put("y",0.5).put("confidence",0.5).put("segmentBreakBefore",true); return result;
    }
    static ObjectNode identity() { return JSON.createObjectNode().put("sourceSchemaVersion",1).put("therapySessionId",SESSION.toString())
            .put("streamId",STREAM.toString()).put("calculationVersion","baseline-v1").put("revision",1); }
    static ObjectNode time() { return JSON.createObjectNode().put("minimum",TIME).put("maximum",TIME); }
    static ArrayNode cells() { var result=JSON.createArrayNode(); for(int i=0;i<100;i++) result.add(i==55?1:0); return result; }
    static ObjectNode numeric(int count,double value) {
        var result=JSON.createObjectNode().put("count",count);
        for(String field:List.of("minimum","maximum","mean")) { if(count==0) result.putNull(field); else result.put(field,value); }
        return result;
    }
}
