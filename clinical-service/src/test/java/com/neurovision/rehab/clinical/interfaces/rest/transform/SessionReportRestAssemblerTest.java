package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;
import com.neurovision.rehab.clinical.application.ports.out.integration.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.DoctorReportIdentityPort.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.SessionLimits;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorTherapySessionReadRepository.TechnicalProgress;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.interfaces.rest.resource.SessionReportResponse;
import com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionReportRestAssemblerTest {
    static final UUID PATIENT = new UUID(0, 1), SESSION = new UUID(0, 2), PLAN = new UUID(0, 3), CONFIG = new UUID(0, 4);
    static final Instant START = Instant.parse("2026-10-01T00:00:00Z"), END = START.plusSeconds(120);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    SessionReportV1 report(Observation analytics, boolean prescribed) {
        TherapeuticExerciseParameters parameters = TherapeuticConfigurationTestData.fixationSnapshot().parameters();
        if (prescribed) parameters = new TherapeuticExerciseParameters.Prescribed(parameters,
                new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 120));
        return new SessionReportV1(PATIENT, new Identity("private-doctor-subject", Availability.AVAILABLE,
                "Synthetic", "Doctor", "Synthetic specialty", null),
                new AssignedPatientNamesPort.Name("AVAILABLE", "Synthetic", "Patient"),
                new SessionReportV1.Execution(SESSION, PLAN, "COMPLETED", START, END, 120, LocalDate.of(2026, 10, 1)),
                new SessionReportV1.FrozenConfiguration(CONFIG, 2, parameters.schemaVersion(), parameters),
                new SessionLimits(1, 10, 25), prescribed
                        ? new TechnicalProgress(TechnicalProgress.Availability.AVAILABLE, CompletionTarget.Criterion.ACTIVE_TIME,
                                120L, 120_000L, 10L, true) : TechnicalProgress.notApplicable(), analytics, END.plusSeconds(60));
    }

    Stream stream(long id, long revision) {
        var source = new Source(SESSION, new UUID(0, id), "baseline-v1", revision, 1, 1);
        var completeness = new Completeness(1, 3, 3, 2, true, List.of(new Range(2, 2)), 1, true,
                new TimeRange(START, END), new Counts(2, 0, 2));
        var cells = new ArrayList<>(Collections.nCopies(100, 0L)); cells.set(0, 2L);
        var grid = new Grid(10, 10, cells);
        var histogram = new ArrayList<>(Collections.nCopies(10, 0L)); histogram.set(5, 2L);
        var empty = new Numeric(0, null, null, null); var ordered = new Ordered(empty, null, null, null);
        var summary = new Summary(source, completeness, new Numeric(2, 0.5, 0.5, 0.5), histogram, grid,
                new Coverage(new BoundingBox(0.1, 0.1, 0.1, 0.1, 0, 0, 0), 1, 0.01),
                new Path(0, empty, 1), new Gameplay(empty, ordered, ordered, ordered));
        return new Stream(summary, new Heatmap(source, completeness, grid, "NORMALIZED_ZERO_TO_ONE", "RAW_GAZE_SAMPLE_COUNT", false),
                new GazePage(source, completeness, 100, 64, true, true,
                        List.of(new GazePoint(1, 0, START, 0.1, 0.1, 0.5, true))));
    }

    void fields(JsonNode node, String... expected) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        assertEquals(Set.of(expected), actual);
    }

    @Test void explicitTopLevelAndIdentityWhitelistIncludesComputedValuesAndFrozenUnits() {
        var source = report(new ConfirmedNoEvidence(SESSION), true);
        JsonNode json = mapper.valueToTree(SessionReportRestAssembler.toResponse(source, mapper));
        fields(json, "schemaVersion", "clinicalPatientId", "issuedAt", "mode", "analyticsNotice", "doctor", "patient",
                "execution", "configuration", "sessionLimits", "technicalProgress", "analytics");
        fields(json.path("doctor"), "availability", "firstName", "lastName", "specialty", "medicalLicense");
        fields(json.path("patient"), "availability", "firstName", "lastName");
        fields(json.path("execution"), "therapySessionId", "therapyPlanId", "status", "startedAt", "endedAt", "activeDurationSeconds", "quotaDate");
        fields(json.path("configuration"), "configurationId", "configurationVersion", "parameterSchemaVersion", "parameters");
        fields(json.path("sessionLimits"), "minimumValidDurationMinutes", "maximumSessionDurationMinutes", "minimumValidSampleCount");
        fields(json.path("technicalProgress"), "availability", "criterion", "target", "activeMilliseconds", "completedRepetitions", "targetReached");
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals(END.plusSeconds(60).toString(), json.path("issuedAt").asText());
        assertEquals("SUSTAINED_TARGET_FIXATION", json.path("mode").asText());
        assertEquals("Sin evidencia analítica disponible", json.path("analyticsNotice").asText());
        assertEquals(mapper.valueToTree(source.configuration().parameters()), json.path("configuration").path("parameters"));
        assertEquals(120, json.path("execution").path("activeDurationSeconds").asLong());
        assertEquals(120, json.path("technicalProgress").path("target").asLong());
        assertEquals(120_000, json.path("technicalProgress").path("activeMilliseconds").asLong());
        assertTrue(json.path("doctor").path("medicalLicense").isNull());
        assertFalse(json.toString().contains("private-doctor-subject")); assertFalse(json.toString().contains("doctorSubject"));
        fields(json.path("analytics"), "therapySessionId", "availability", "streams");
        assertEquals("CONFIRMED_NO_EVIDENCE", json.path("analytics").path("availability").asText());
        assertTrue(json.path("analytics").path("streams").isEmpty());
    }

    @Test void projectsEveryEvidenceBranchWithoutMergingStreamsOrLosingWindowAndProvenance() {
        var evidence = new ObservedEvidence(SESSION, 0, 20, true, List.of(stream(5, 1), stream(6, 7)));
        JsonNode json = mapper.valueToTree(SessionReportRestAssembler.toResponse(report(evidence, false), mapper));
        assertTrue(json.path("analyticsNotice").isNull());
        var analytics = json.path("analytics");
        fields(analytics, "therapySessionId", "availability", "discoveryPage", "discoverySize", "hasNext", "streams");
        assertEquals(0, analytics.path("discoveryPage").asInt()); assertEquals(20, analytics.path("discoverySize").asInt());
        assertTrue(analytics.path("hasNext").asBoolean()); assertEquals(2, analytics.path("streams").size());
        for (int i = 0; i < 2; i++) {
            var stream = analytics.path("streams").get(i); fields(stream, "summary", "heatmap", "gazePlot");
            var summary = stream.path("summary");
            fields(summary, "source", "completeness", "confidence", "confidenceHistogram", "heatmap", "coverage", "gazePath", "gameplay");
            var source = summary.path("source");
            fields(source, "therapySessionId", "streamId", "calculationVersion", "revision", "contractVersion", "sourceSchemaVersion");
            assertEquals(new UUID(0, 5 + i).toString(), source.path("streamId").asText());
            assertEquals(i == 0 ? 1 : 7, source.path("revision").asLong());
            assertEquals("baseline-v1", source.path("calculationVersion").asText());
            var complete = summary.path("completeness");
            fields(complete, "minimumSequence", "maximumSequence", "highSequence", "missingSequenceRangeCount", "missingSequenceRangesTruncated",
                    "lateArrivalCount", "unknownSessionBoundary", "missingSequenceRanges", "observedTimeRange", "sampleCounts");
            assertTrue(complete.path("unknownSessionBoundary").asBoolean());
            assertTrue(complete.path("missingSequenceRangesTruncated").asBoolean());
            assertEquals(2, complete.path("missingSequenceRangeCount").asLong());
            fields(complete.path("missingSequenceRanges").get(0), "fromInclusive", "toInclusive");
            fields(complete.path("observedTimeRange"), "minimum", "maximum");
            fields(complete.path("sampleCounts"), "gaze", "gameplay", "total");
            fields(summary.path("confidence"), "count", "minimum", "maximum", "mean");
            assertEquals(0.5, summary.path("confidence").path("mean").asDouble());
            assertEquals(10, summary.path("confidenceHistogram").size());
            assertEquals(2, summary.path("confidenceHistogram").get(5).asLong());
            fields(summary.path("heatmap"), "rows", "columns", "cellCounts");
            assertEquals(100, summary.path("heatmap").path("cellCounts").size());
            fields(summary.path("coverage"), "occupiedCellCount", "occupiedCellRatio", "boundingBox");
            fields(summary.path("coverage").path("boundingBox"), "minimumX", "minimumY", "maximumX", "maximumY", "width", "height", "area");
            fields(summary.path("gazePath"), "totalDistance", "excludedNonPositiveDeltaCount", "velocity");
            var gameplay = summary.path("gameplay"); fields(gameplay, "playerTargetDistance", "score", "speed", "progress");
            for (String name : List.of("score", "speed", "progress")) {
                fields(gameplay.path(name), "distribution", "first", "last", "signedDelta");
                assertTrue(gameplay.path(name).path("first").isNull());
            }
            var heatmap = stream.path("heatmap");
            fields(heatmap, "source", "completeness", "grid", "coordinateSpace", "cellWeight", "confidenceFiltered");
            assertEquals(source, heatmap.path("source")); assertEquals(complete, heatmap.path("completeness"));
            assertEquals(summary.path("heatmap"), heatmap.path("grid"));
            assertEquals("NORMALIZED_ZERO_TO_ONE", heatmap.path("coordinateSpace").asText());
            assertFalse(heatmap.path("confidenceFiltered").asBoolean());
            var gaze = stream.path("gazePlot");
            fields(gaze, "source", "completeness", "pageSize", "sourceBatchScanLimit", "startsAtBeginning", "hasNext", "points");
            assertEquals(source, gaze.path("source")); assertEquals(complete, gaze.path("completeness"));
            assertEquals(100, gaze.path("pageSize").asInt()); assertEquals(64, gaze.path("sourceBatchScanLimit").asInt());
            assertTrue(gaze.path("hasNext").asBoolean()); assertTrue(gaze.path("startsAtBeginning").asBoolean());
            var point = gaze.path("points").get(0);
            fields(point, "sequence", "gazeSampleIndex", "effectiveTime", "x", "y", "confidence", "segmentBreakBefore");
            assertEquals(START.toString(), point.path("effectiveTime").asText());
            assertEquals(0.1, point.path("x").asDouble()); assertTrue(point.path("segmentBreakBefore").asBoolean());
        }
        assertEquals("NOT_APPLICABLE", json.path("technicalProgress").path("availability").asText());
        assertTrue(json.path("technicalProgress").path("target").isNull());
        assertTrue(json.path("technicalProgress").path("activeMilliseconds").isNull());
    }

    @Test void transportSnapshotDefensivelyCopiesInputAndEveryReturnedNestedTree() {
        var input = mapper.createObjectNode(); input.putObject("nested").putArray("values").add(1);
        var response = new SessionReportResponse(input); input.withObject("nested").put("injected", "private");
        var first = (ObjectNode) response.representation(); first.withObject("nested").removeAll();
        assertEquals(1, response.representation().path("nested").path("values").get(0).asInt());
        var report = SessionReportRestAssembler.toResponse(report(new ConfirmedNoEvidence(SESSION), false), mapper);
        ((ObjectNode) report.representation()).removeAll();
        assertEquals(PATIENT.toString(), report.representation().path("clinicalPatientId").asText());
        assertEquals("SessionReportResponse[redacted]", report.toString());
        assertFalse(report.toString().contains("Synthetic"));
    }
}
