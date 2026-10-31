package com.neurovision.rehab.clinical.application.internal.queryservices;

import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.application.internal.queryservices.ComposeSessionReportServiceTest.*;

class SessionReportV1Test {
    static Stream stream(long id,long revision) {
        var source = new Source(SESSION,new UUID(0,id),"baseline-v1",revision,1,1);
        var gaps = new ArrayList<>(List.of(new Range(2,2)));
        var complete = new Completeness(1,3,3,1,false,gaps,1,true,new TimeRange(START,END),new Counts(2,0,2));
        var cells = new ArrayList<>(Collections.nCopies(100,0L)); cells.set(0,2L);
        var grid = new Grid(10,10,cells);
        var histogram = new ArrayList<>(Collections.nCopies(10,0L)); histogram.set(5,2L);
        var empty = new Numeric(0,null,null,null); var ordered = new Ordered(empty,null,null,null);
        var summary = new Summary(source,complete,new Numeric(2,0.5,0.5,0.5),histogram,grid,
                new Coverage(new BoundingBox(0.1,0.1,0.1,0.1,0,0,0),1,0.01),new Path(0,empty,1),
                new Gameplay(empty,ordered,ordered,ordered));
        var heatmap = new Heatmap(source,complete,grid,"NORMALIZED_ZERO_TO_ONE","RAW_GAZE_SAMPLE_COUNT",false);
        var points = new ArrayList<>(List.of(new GazePoint(1,0,START,0.1,0.1,0.5,true)));
        var gaze = new GazePage(source,complete,1,64,true,true,points);
        gaps.clear(); cells.clear(); histogram.clear(); points.clear();
        return new Stream(summary,heatmap,gaze);
    }
    @Test void copiesAllNestedCollectionsAndRetainsSourceValues() {
        var stream = stream(1,3);
        assertEquals(2,stream.summary().heatmap().cellCounts().getFirst());
        assertEquals(0.5,stream.summary().confidence().mean());
        assertEquals(1,stream.summary().completeness().missingSequenceRanges().size());
        assertThrows(UnsupportedOperationException.class,()->stream.summary().heatmap().cellCounts().clear());
        assertThrows(UnsupportedOperationException.class,()->stream.summary().confidenceHistogram().clear());
        assertThrows(UnsupportedOperationException.class,()->stream.summary().completeness().missingSequenceRanges().clear());
        assertThrows(UnsupportedOperationException.class,()->stream.gazePlot().points().clear());
        var observed = new ObservedEvidence(SESSION,0,1,false,List.of(stream));
        assertThrows(UnsupportedOperationException.class,()->observed.streams().clear());
    }
    @Test void rejectsEmptyObservedPagesRatherThanTreatingThemAsConfirmedAbsence() {
        assertThrows(IllegalArgumentException.class,()->new ObservedEvidence(SESSION,1,50,false,List.of()));
        assertThrows(IllegalArgumentException.class,()->new ObservedEvidence(SESSION,0,50,true,List.of()));
        assertThrows(IllegalArgumentException.class,()->new ObservedEvidence(SESSION,0,51,false,List.of(stream(1,1))));
        assertThrows(IllegalArgumentException.class,()->new ObservedEvidence(SESSION,0,2,false,List.of(stream(1,1),stream(1,1))));
        assertThrows(IllegalArgumentException.class,()->new ObservedEvidence(new UUID(0,99),0,1,false,List.of(stream(1,1))));
    }
    @Test void rejectsMismatchedSourceIdentityVersionAndRevisionAcrossEvidence() {
        var stream = stream(1,1); var other = stream(2,2);
        assertThrows(IllegalArgumentException.class,()->new Stream(stream.summary(),other.heatmap(),stream.gazePlot()));
        assertThrows(IllegalArgumentException.class,()->new Stream(stream.summary(),stream.heatmap(),other.gazePlot()));
        var revised = stream(1,2);
        assertThrows(IllegalArgumentException.class,()->new Stream(stream.summary(),revised.heatmap(),stream.gazePlot()));
        assertThrows(IllegalArgumentException.class,()->new Source(SESSION,new UUID(0,1),"future-v2",1,1,1));
        assertThrows(IllegalArgumentException.class,()->new Source(SESSION,new UUID(0,1),"baseline-v1",1,1,2));
    }
    @Test void rejectsCorruptionAndKeepsUnknownBoundaryAndPageLimits() {
        var stream = stream(1,1); var source = stream.summary().source(); var c = stream.summary().completeness();
        assertThrows(IllegalArgumentException.class,()->new Completeness(1,3,3,0,false,List.of(),0,false,c.observedTimeRange(),c.sampleCounts()));
        assertThrows(IllegalArgumentException.class,()->new Counts(2,0,3));
        assertThrows(IllegalArgumentException.class,()->new Numeric(1,0.8,0.9,0.5));
        assertThrows(IllegalArgumentException.class,()->new Numeric(0,0.0,null,null));
        assertThrows(IllegalArgumentException.class,()->new Grid(10,10,List.of(1L)));
        assertThrows(IllegalArgumentException.class,()->new GazePoint(1,0,START,Double.NaN,0.1,0.5,true));
        assertThrows(IllegalArgumentException.class,()->new GazePage(source,c,501,64,true,true,List.of()));
        assertThrows(IllegalArgumentException.class,()->new GazePage(source,c,1,65,true,true,List.of()));
        assertThrows(IllegalArgumentException.class,()->new GazePage(source,c,1,64,true,false,stream.gazePlot().points()));
        assertThrows(IllegalArgumentException.class,()->new GazePage(source,c,1,64,true,true,List.of(new GazePoint(2,0,START,0.1,0.1,0.5,true))));
    }
    @Test void rejectsMismatchedCompletenessAndSummaryCountsWithoutRepairingThem() {
        var stream = stream(1,1); var summary = stream.summary(); var c = summary.completeness();
        var other = new Completeness(1,3,3,1,false,List.of(new Range(2,2)),2,true,c.observedTimeRange(),c.sampleCounts());
        var heatmap = new Heatmap(summary.source(),other,summary.heatmap(),"NORMALIZED_ZERO_TO_ONE","RAW_GAZE_SAMPLE_COUNT",false);
        assertThrows(IllegalArgumentException.class,()->new Stream(summary,heatmap,stream.gazePlot()));
        assertThrows(IllegalArgumentException.class,()->new Summary(summary.source(),c,new Numeric(1,0.5,0.5,0.5),
                summary.confidenceHistogram(),summary.heatmap(),summary.coverage(),summary.gazePath(),summary.gameplay()));
        var histogram = new ArrayList<>(summary.confidenceHistogram()); histogram.set(5,1L);
        assertThrows(IllegalArgumentException.class,()->new Summary(summary.source(),c,summary.confidence(),
                histogram,summary.heatmap(),summary.coverage(),summary.gazePath(),summary.gameplay()));
    }
    @Test void retainsExplicitGapTruncationAndLaterPageSelection() {
        var c = stream(1,1).summary().completeness();
        var truncated = new Completeness(1,3,3,2,true,c.missingSequenceRanges(),1,true,c.observedTimeRange(),c.sampleCounts());
        assertEquals(2,truncated.missingSequenceRangeCount()); assertTrue(truncated.missingSequenceRangesTruncated());
        var laterPage = new ObservedEvidence(SESSION,1,1,false,List.of(stream(1,1)));
        assertEquals(1,laterPage.discoveryPage());
        assertThrows(IllegalArgumentException.class,()->new Completeness(1,3,3,2,false,c.missingSequenceRanges(),1,true,c.observedTimeRange(),c.sampleCounts()));
    }
    @Test void reportAndNestedSensitiveValuesHaveRedactedStrings() {
        var service = new ComposeSessionReportServiceTest();
        var report = service.compose(); var stream = stream(1,1);
        for (Object value : List.of(report,report.execution(),report.configuration(),report.doctor(),report.patient(),
                stream,stream.summary(),stream.summary().source(),stream.heatmap(),stream.gazePlot(),
                stream.gazePlot().points().getFirst(),stream.summary().coverage().boundingBox(),new ConfirmedNoEvidence(SESSION))) {
            assertTrue(value.toString().contains("redacted"),value.getClass().getSimpleName());
            assertFalse(value.toString().contains(SESSION.toString())); assertFalse(value.toString().contains("Synthetic"));
        }
    }
}
