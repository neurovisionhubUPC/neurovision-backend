package com.neurovision.rehab.clinical.interfaces.rest.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.interfaces.rest.resource.*;

/** Explicit outward projection: never serialize an application identity or report and remove fields. */
public final class SessionReportRestAssembler {
    private SessionReportRestAssembler() {}

    public static SessionReportResponse toResponse(SessionReportV1 report, ObjectMapper mapper) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", 1).put("clinicalPatientId", report.clinicalPatientId().toString())
                .put("issuedAt", report.issuedAt().toString()).put("mode", report.mode())
                .put("analyticsNotice", report.analyticsNotice());
        var doctor = report.doctor();
        root.putObject("doctor").put("availability", doctor.availability().name())
                .put("firstName", doctor.firstName()).put("lastName", doctor.lastName())
                .put("specialty", doctor.specialty()).put("medicalLicense", doctor.medicalLicense());
        var patient = report.patient();
        root.putObject("patient").put("availability", patient.status())
                .put("firstName", patient.firstName()).put("lastName", patient.lastName());
        var execution = report.execution();
        root.putObject("execution").put("therapySessionId", execution.therapySessionId().toString())
                .put("therapyPlanId", execution.therapyPlanId().toString()).put("status", execution.status())
                .put("startedAt", execution.startedAt().toString()).put("endedAt", execution.endedAt().toString())
                .put("activeDurationSeconds", execution.activeDurationSeconds()).put("quotaDate", execution.quotaDate().toString());
        var configuration = report.configuration();
        var frozen = root.putObject("configuration").put("configurationId", configuration.configurationId().toString())
                .put("configurationVersion", configuration.configurationVersion())
                .put("parameterSchemaVersion", configuration.parameterSchemaVersion());
        // Same parameter encoding as the existing Clinical session/plan assemblers.
        frozen.set("parameters", mapper.valueToTree(configuration.parameters()));
        var limits = report.sessionLimits();
        root.set("sessionLimits", mapper.valueToTree(new DoctorTherapySessionReadResponse.SessionLimits(
                limits.minimumValidDurationMinutes(), limits.maximumSessionDurationMinutes(), limits.minimumValidSampleCount())));
        var progress = report.technicalProgress();
        root.set("technicalProgress", mapper.valueToTree(new DoctorTherapySessionReadResponse.TechnicalProgress(
                progress.availability().name(), progress.criterion() == null ? null : progress.criterion().name(),
                progress.target(), progress.activeMilliseconds(), progress.completedRepetitions(), progress.targetReached())));
        var analytics = root.putObject("analytics").put("therapySessionId", report.analytics().therapySessionId().toString());
        var streams = analytics.putArray("streams");
        if (report.analytics() instanceof ObservedEvidence observed) {
            analytics.put("availability", "OBSERVED_EVIDENCE").put("discoveryPage", observed.discoveryPage())
                    .put("discoverySize", observed.discoverySize()).put("hasNext", observed.hasNext());
            for (var stream : observed.streams()) streams.add(stream(stream));
        } else {
            analytics.put("availability", "CONFIRMED_NO_EVIDENCE");
        }
        if (report.visualSearch() != null) {
            var selected = report.visualSearch();
            root.putObject("visualSearch").put("calculationVersion",selected.calculationVersion())
                    .put("therapySessionId",selected.therapySessionId().toString()).put("configurationId",selected.configurationId().toString())
                    .put("configurationVersion",selected.configurationVersion()).put("dataOrigin",selected.dataOrigin().name())
                    .put("reportedTrialCount",selected.reportedTrialCount()).put("hitCount",selected.hitCount())
                    .put("omissionCount",selected.omissionCount()).put("interruptedCount",selected.interruptedCount())
                    .put("eligibleSearchCount",selected.eligibleSearchCount()).put("medianSearchDurationMs",selected.medianSearchDurationMs());
        }
        if (report.standardScanning() != null) {
            var selected = report.standardScanning();
            root.putObject("standardScanning").put("calculationVersion",selected.calculationVersion())
                    .put("therapySessionId",selected.therapySessionId().toString()).put("configurationId",selected.configurationId().toString())
                    .put("configurationVersion",selected.configurationVersion()).put("dataOrigin",selected.dataOrigin().name())
                    .put("reportedTrialCount",selected.reportedTrialCount()).put("detectedTargetCount",selected.detectedTargetCount())
                    .put("omissionCount",selected.omissionCount()).put("interruptedCount",selected.interruptedCount())
                    .put("eligibleLocalizationCount",selected.eligibleLocalizationCount()).put("medianLocalizationDurationMs",selected.medianLocalizationDurationMs());
        }
        if (report.sustainedFixation() != null) {
            var selected = report.sustainedFixation();
            root.putObject("sustainedFixation").put("calculationVersion",selected.calculationVersion())
                    .put("therapySessionId",selected.therapySessionId().toString()).put("configurationId",selected.configurationId().toString())
                    .put("configurationVersion",selected.configurationVersion()).put("dataOrigin",selected.dataOrigin().name())
                    .put("reportedTrialCount",selected.reportedTrialCount()).put("totalInsideTargetMs",selected.totalInsideTargetMs())
                    .put("completedTargetCount",selected.completedTargetCount());
        }
        if (report.compensatorySaccade() != null) {
            var selected = report.compensatorySaccade();
            root.putObject("compensatorySaccade").put("calculationVersion",selected.calculationVersion())
                    .put("therapySessionId",selected.therapySessionId().toString()).put("configurationId",selected.configurationId().toString())
                    .put("configurationVersion",selected.configurationVersion()).put("dataOrigin",selected.dataOrigin().name())
                    .put("reportedTrialCount",selected.reportedTrialCount()).put("reachedTargetCount",selected.reachedTargetCount())
                    .put("medianTimeToTargetMs",selected.medianTimeToTargetMs());
        }
        if (report.systematicExploration() != null) {
            var selected = report.systematicExploration();
            var section = root.putObject("systematicExploration").put("calculationVersion",selected.calculationVersion())
                    .put("therapySessionId",selected.therapySessionId().toString()).put("configurationId",selected.configurationId().toString())
                    .put("configurationVersion",selected.configurationVersion()).put("dataOrigin",selected.dataOrigin().name())
                    .put("reportedTrialCount",selected.reportedTrialCount());
            var rows = section.putArray("traversals");
            for (var row : selected.traversals()) rows.addObject().put("trialId",row.trialId()).put("outcome",row.outcome().name())
                    .put("visitedRegionCount",row.visitedRegionCount()).put("coveragePercent",row.coveragePercent()).put("elapsedMs",row.elapsedMs());
        }
        return new SessionReportResponse(root);
    }

    private static ObjectNode stream(Stream stream) {
        var node = JsonNodeFactory.instance.objectNode();
        var summary = stream.summary();
        var s = node.putObject("summary");
        provenance(s, summary.source(), summary.completeness());
        s.set("confidence", numeric(summary.confidence()));
        numbers(s.putArray("confidenceHistogram"), summary.confidenceHistogram());
        s.set("heatmap", grid(summary.heatmap()));
        var coverage = summary.coverage();
        var c = s.putObject("coverage").put("occupiedCellCount", coverage.occupiedCellCount())
                .put("occupiedCellRatio", coverage.occupiedCellRatio());
        if (coverage.boundingBox() == null) c.putNull("boundingBox");
        else {
            var b = coverage.boundingBox();
            c.putObject("boundingBox").put("minimumX", b.minimumX()).put("minimumY", b.minimumY())
                    .put("maximumX", b.maximumX()).put("maximumY", b.maximumY())
                    .put("width", b.width()).put("height", b.height()).put("area", b.area());
        }
        var path = summary.gazePath();
        var p = s.putObject("gazePath").put("totalDistance", path.totalDistance())
                .put("excludedNonPositiveDeltaCount", path.excludedNonPositiveDeltaCount());
        p.set("velocity", numeric(path.velocity()));
        var gameplay = summary.gameplay();
        var g = s.putObject("gameplay");
        g.set("playerTargetDistance", numeric(gameplay.playerTargetDistance()));
        g.set("score", ordered(gameplay.score()));
        g.set("speed", ordered(gameplay.speed()));
        g.set("progress", ordered(gameplay.progress()));
        var heatmap = stream.heatmap();
        var h = node.putObject("heatmap");
        provenance(h, heatmap.source(), heatmap.completeness());
        h.set("grid", grid(heatmap.grid()));
        h.put("coordinateSpace", heatmap.coordinateSpace()).put("cellWeight", heatmap.cellWeight())
                .put("confidenceFiltered", heatmap.confidenceFiltered());
        var gaze = stream.gazePlot();
        var gazeNode = node.putObject("gazePlot");
        provenance(gazeNode, gaze.source(), gaze.completeness());
        gazeNode.put("pageSize", gaze.pageSize()).put("sourceBatchScanLimit", gaze.sourceBatchScanLimit())
                .put("startsAtBeginning", gaze.startsAtBeginning()).put("hasNext", gaze.hasNext());
        var points = gazeNode.putArray("points");
        for (var point : gaze.points()) points.addObject().put("sequence", point.sequence())
                .put("gazeSampleIndex", point.gazeSampleIndex()).put("effectiveTime", point.effectiveTime().toString())
                .put("x", point.x()).put("y", point.y()).put("confidence", point.confidence())
                .put("segmentBreakBefore", point.segmentBreakBefore());
        return node;
    }

    private static void provenance(ObjectNode node, Source source, Completeness complete) {
        node.putObject("source").put("therapySessionId", source.therapySessionId().toString())
                .put("streamId", source.streamId().toString()).put("calculationVersion", source.calculationVersion())
                .put("revision", source.revision()).put("contractVersion", source.contractVersion())
                .put("sourceSchemaVersion", source.sourceSchemaVersion());
        var c = node.putObject("completeness").put("minimumSequence", complete.minimumSequence())
                .put("maximumSequence", complete.maximumSequence()).put("highSequence", complete.highSequence())
                .put("missingSequenceRangeCount", complete.missingSequenceRangeCount())
                .put("missingSequenceRangesTruncated", complete.missingSequenceRangesTruncated())
                .put("lateArrivalCount", complete.lateArrivalCount()).put("unknownSessionBoundary", complete.unknownSessionBoundary());
        var gaps = c.putArray("missingSequenceRanges");
        for (var range : complete.missingSequenceRanges()) gaps.addObject()
                .put("fromInclusive", range.fromInclusive()).put("toInclusive", range.toInclusive());
        if (complete.observedTimeRange() == null) c.putNull("observedTimeRange");
        else c.putObject("observedTimeRange").put("minimum", complete.observedTimeRange().minimum().toString())
                .put("maximum", complete.observedTimeRange().maximum().toString());
        var counts = complete.sampleCounts();
        c.putObject("sampleCounts").put("gaze", counts.gaze()).put("gameplay", counts.gameplay()).put("total", counts.total());
    }

    private static ObjectNode numeric(Numeric value) {
        return JsonNodeFactory.instance.objectNode().put("count", value.count()).put("minimum", value.minimum())
                .put("maximum", value.maximum()).put("mean", value.mean());
    }

    private static ObjectNode ordered(Ordered value) {
        var node = JsonNodeFactory.instance.objectNode().put("first", value.first()).put("last", value.last())
                .put("signedDelta", value.signedDelta());
        node.set("distribution", numeric(value.distribution()));
        return node;
    }

    private static ObjectNode grid(Grid value) {
        var node = JsonNodeFactory.instance.objectNode().put("rows", value.rows()).put("columns", value.columns());
        numbers(node.putArray("cellCounts"), value.cellCounts());
        return node;
    }

    private static void numbers(ArrayNode node, java.util.List<Long> values) { values.forEach(node::add); }
}
