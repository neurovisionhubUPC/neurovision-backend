package com.neurovision.rehab.clinical.infrastructure.reporting;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportPdfRenderer;
import com.neurovision.rehab.clinical.infrastructure.integration.JsonSessionReportSnapshotEncoder;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.Grid;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.fontbox.ttf.TTFParser;

/** A single fixed text/table template. PDF bytes are transient; the stored report is never rewritten. */
public final class PdfBoxSessionReportRenderer implements SessionReportPdfRenderer {
    private final ObjectReader reader;
    private final JsonSessionReportSnapshotEncoder snapshotCodec;
    public PdfBoxSessionReportRenderer(ObjectMapper mapper) {
        snapshotCodec = new JsonSessionReportSnapshotEncoder(Objects.requireNonNull(mapper));
        reader = Objects.requireNonNull(mapper).reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public byte[] render(Snapshot snapshot) {
        try {
            JsonNode report = reader.readTree(snapshot.reportJson());
            validate(report, snapshot);
            JsonNode visualSearch = null;
            if (report.has("visualSearch")) {
                // Reuse the saved-copy validator; only this optional subtree gets exact decimal decoding.
                snapshotCodec.visualSearchSelection(snapshot.reportJson(), snapshot.therapySessionId());
                JsonNode exact = reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(snapshot.reportJson());
                visualSearch = exact.get("visualSearch");
            }
            JsonNode standardScanning = null;
            if (report.has("standardScanning")) {
                snapshotCodec.standardScanningSelection(snapshot.reportJson(), snapshot.therapySessionId());
                JsonNode exact = reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(snapshot.reportJson());
                standardScanning = exact.get("standardScanning");
            }
            JsonNode sustainedFixation = report.get("sustainedFixation");
            if (sustainedFixation != null)
                snapshotCodec.sustainedFixationSelection(snapshot.reportJson(), snapshot.therapySessionId());
            JsonNode compensatorySaccade = null;
            if (report.has("compensatorySaccade")) {
                snapshotCodec.compensatorySaccadeSelection(snapshot.reportJson(), snapshot.therapySessionId());
                JsonNode exact = reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(snapshot.reportJson());
                compensatorySaccade = exact.get("compensatorySaccade");
            }
            JsonNode systematicExploration = report.get("systematicExploration");
            if (systematicExploration != null)
                snapshotCodec.systematicExplorationSelection(snapshot.reportJson(), snapshot.therapySessionId());
            try (var document = new PDDocument();
                    var input = Objects.requireNonNull(getClass().getResourceAsStream("/fonts/DejaVuSans.ttf"));
                    var fontBytes = new RandomAccessReadBuffer(input);
                    var trueType = new TTFParser().parse(fontBytes);
                    var bytes = new ByteArrayOutputStream()) {
                // Keep Latin text measurement, output and extraction aligned; this is not a complex-script renderer.
                trueType.setEnableGsub(false);
                var font = PDType0Font.load(document, trueType, true);
                try (var page = new Layout(document, font)) {
                    page.section("Saved report");
                    page.row("Copy reference", snapshot.id().toString());
                    page.row("Copy created (UTC)", snapshot.createdAt().toString());
                    fields(page, report, "schemaVersion", "issuedAt", "mode");
                    page.section("Patient - as saved");
                    fields(page, report.path("patient"), "availability", "firstName", "lastName");
                    page.section("Professional - as saved");
                    fields(page, report.path("doctor"), "availability", "firstName", "lastName", "specialty", "medicalLicense");
                    page.section("Session execution - technical fulfillment only");
                    fields(page, report.path("execution"), "therapySessionId", "therapyPlanId", "status", "startedAt", "endedAt", "activeDurationSeconds", "quotaDate");
                    page.section("Frozen configuration");
                    fields(page, report.path("configuration"), "configurationId", "configurationVersion", "parameterSchemaVersion");
                    parameters(page, report.path("configuration").path("parameters"), "parameters");
                    fields(page, report.path("sessionLimits"), "minimumValidDurationMinutes", "maximumSessionDurationMinutes", "minimumValidSampleCount");
                    page.section("Technical progress - not therapeutic efficacy");
                    fields(page, report.path("technicalProgress"), "availability", "criterion", "target", "activeMilliseconds", "completedRepetitions", "targetReached");
                    page.section("Analytical evidence - observed data only");
                    fields(page, report.path("analytics"), "availability");
                    if (report.path("analytics").path("availability").asText().equals("CONFIRMED_NO_EVIDENCE")) {
                        page.row("Evidence notice", "Sin evidencia analítica disponible");
                    } else {
                        fields(page, report.path("analytics"), "discoveryPage", "discoverySize", "hasNext");
                        int index = 0;
                        for (var stream : report.path("analytics").path("streams")) {
                            page.section("Observed stream " + ++index + " - independent results");
                            for (String product : List.of("summary", "heatmap", "gazePlot")) {
                                var value = stream.path(product);
                                page.section(product + " provenance and completeness");
                                fields(page, value.path("source"), "streamId", "calculationVersion", "revision", "contractVersion", "sourceSchemaVersion");
                                fields(page, value.path("completeness"), "minimumSequence", "maximumSequence", "highSequence", "missingSequenceRangeCount", "missingSequenceRangesTruncated", "lateArrivalCount", "unknownSessionBoundary");
                                fields(page, value.path("completeness").path("sampleCounts"), "gaze", "gameplay", "total");
                                parameters(page, value.path("completeness").path("observedTimeRange"), "observedTimeRange (UTC)");
                                parameters(page, value.path("completeness").path("missingSequenceRanges"), "missingSequenceRanges");
                            }
                            var summary = stream.path("summary");
                            parameters(page, summary.path("confidence"), "reported confidence");
                            parameters(page, summary.path("confidenceHistogram"), "reported confidence histogram");
                            parameters(page, summary.path("coverage"), "observed geometric coverage (normalized)");
                            parameters(page, summary.path("gazePath"), "estimated path (normalized units; velocity per second)");
                            parameters(page, summary.path("gameplay"), "gameplay values (producer-defined; not accuracy)");
                            fields(page, stream.path("heatmap"), "coordinateSpace", "cellWeight", "confidenceFiltered");
                            heatmap(page, stream.path("heatmap"));
                            fields(page, stream.path("gazePlot"), "pageSize", "sourceBatchScanLimit", "startsAtBeginning", "hasNext");
                            gazePlot(page, stream.path("gazePlot"));
                        }
                    }
                    trialResults(page, visualSearch, false);
                    if (standardScanning != null) trialResults(page, standardScanning, true);
                    if (sustainedFixation != null) sustainedFixationResults(page, sustainedFixation);
                    if (compensatorySaccade != null) compensatorySaccadeResults(page, compensatorySaccade);
                    if (systematicExploration != null) systematicExplorationResults(page, systematicExploration);
                    page.section("Interpretation limits");
                    page.row("Important", "This technical report is not a diagnosis, evidence of visual-field recovery or proof of therapeutic efficacy.");
                    page.row("Technical completion", "COMPLETED means the programmed technical objective was met; it does not guarantee complete Telemetry persistence.");
                    page.row("Missing data", "Not available and NOT_APPLICABLE do not mean zero. Results reflect only this saved copy; no services were refreshed.");
                    page.row("Analytical limits", "Sample counts are not trials or hits. Reported confidence is not a clinical quality assessment. Geometric coverage is not prescribed exploration compliance.");
                    page.row("Visualizations", "Heatmaps show raw observed gaze-sample counts, not fixation time. Gaze plots show estimated points in saved order, not physiological fixations or saccades.");
                }
                document.getDocumentInformation().setTitle("Session technical report");
                document.save(bytes);
                return bytes.toByteArray();
            }
        } catch (Exception unavailable) {
            // Do not attach parser/font exceptions: their messages may contain stored personal data.
            throw new IllegalStateException("Session report PDF unavailable");
        }
    }

    private static void trialResults(Layout page, JsonNode summary, boolean standardScanning) throws IOException {
        // Fixed labels, closed origins and bounded numeric values fit this existing-layout page block.
        page.space(summary == null ? 64 : 300);
        page.section(standardScanning ? "Standard Scanning - reported final results" : "Visual Search - reported final results");
        if (summary == null) {
            page.row("Results", "Visual Search results not included");
            return;
        }
        String origin = summary.path("dataOrigin").textValue();
        page.row("Data origin", origin);
        page.row("Calculation version", summary.path("calculationVersion").textValue());
        page.row("Reported attempts", summary.path("reportedTrialCount").asText());
        page.row(standardScanning ? "Detected targets" : "Hits", summary.path(standardScanning ? "detectedTargetCount" : "hitCount").asText());
        page.row("Omissions", summary.path("omissionCount").asText());
        page.row("Interruptions", summary.path("interruptedCount").asText());
        page.row(standardScanning ? "Eligible localizations" : "Eligible searches", summary.path(standardScanning ? "eligibleLocalizationCount" : "eligibleSearchCount").asText());
        var median = summary.path(standardScanning ? "medianLocalizationDurationMs" : "medianSearchDurationMs");
        page.row(standardScanning ? "Median localization duration (ms)" : "Median search duration (ms)",
                median.isNull() ? "Not available" : median.decimalValue().toPlainString());
        page.row("Meaning", "Reported final results only; not total presentations or a completeness guarantee.");
        page.row("Source limitation", origin.equals("SYNTHETIC")
                ? "SYNTHETIC: test data, not patient evidence."
                : "PRODUCER_REPORTED: producer-reported outcomes; not algorithmically verified clinical success.");
    }

    private static void sustainedFixationResults(Layout page, JsonNode summary) throws IOException {
        page.space(300);
        page.section("Sustained Fixation - reported final results");
        String origin = summary.path("dataOrigin").textValue();
        page.row("Data origin", origin);
        page.row("Calculation version", summary.path("calculationVersion").textValue());
        page.row("Reported attempts", summary.path("reportedTrialCount").asText());
        page.row("Total valid time inside the target (ms)", summary.path("totalInsideTargetMs").asText());
        page.row("Completed targets", summary.path("completedTargetCount").asText());
        page.row("Meaning", "Accumulated valid time across reported attempts; not continuous dwell or physiological fixation.");
        page.row("Source limitation", origin.equals("SYNTHETIC")
                ? "SYNTHETIC: test data, not patient evidence."
                : "PRODUCER_REPORTED: producer-reported outcomes; not algorithmically verified clinical success.");
    }

    private static void compensatorySaccadeResults(Layout page, JsonNode summary) throws IOException {
        page.space(300);
        page.section("Compensatory Saccade - reported final results");
        String origin = summary.path("dataOrigin").textValue();
        page.row("Data origin", origin);
        page.row("Calculation version", summary.path("calculationVersion").textValue());
        page.row("Reported attempts", summary.path("reportedTrialCount").asText());
        page.row("Reached targets", summary.path("reachedTargetCount").asText());
        var median = summary.path("medianTimeToTargetMs");
        page.row("Median time to target (ms)", median.isNull() ? "Not available" : median.decimalValue().toPlainString());
        page.row("Meaning", "Successful attempts only; includes confirmation dwell. Not physiological saccade duration or evidence of clinical efficacy.");
        page.row("Source limitation", origin.equals("SYNTHETIC")
                ? "SYNTHETIC: test data, not patient evidence."
                : "PRODUCER_REPORTED: producer-reported outcomes; not algorithmically verified clinical success.");
    }

    private static void systematicExplorationResults(Layout page, JsonNode summary) throws IOException {
        page.space(240);
        page.section("Systematic Exploration - reported final results");
        String origin = summary.path("dataOrigin").textValue();
        page.row("Data origin", origin);
        page.row("Calculation version", summary.path("calculationVersion").textValue());
        page.row("Reported traversals", summary.path("reportedTrialCount").asText());
        page.row("Meaning", "Saved producer-reported region visits and elapsed durations; not proof of clinical efficacy.");
        page.row("Source limitation", origin.equals("SYNTHETIC")
                ? "SYNTHETIC: test data, not patient evidence."
                : "PRODUCER_REPORTED: producer-reported outcomes; not algorithmically verified clinical success.");
        if (summary.path("traversals").isEmpty()) page.row("Results", "No reported traversals");
        for (var traversal : summary.path("traversals")) {
            // Existing row wrapping and page continuation preserve every saved traversal without truncation.
            page.space(125);
            page.row("Traversal reference", traversal.path("trialId").textValue());
            page.row("Outcome", traversal.path("outcome").textValue());
            page.row("Visited regions", traversal.path("visitedRegionCount").asText() + " of 4");
            page.row("Coverage (%)", traversal.path("coveragePercent").asText());
            page.row("Elapsed duration (ms)", traversal.path("elapsedMs").asText());
        }
    }

    private static void heatmap(Layout page, JsonNode value) throws IOException {
        var node = value.path("grid");
        if (!node.isObject() || !node.path("rows").isIntegralNumber() || !node.path("rows").canConvertToInt()
                || !node.path("columns").isIntegralNumber() || !node.path("columns").canConvertToInt()
                || !node.path("cellCounts").isArray() || node.path("cellCounts").size() != 100
                || !value.path("coordinateSpace").asText().equals("NORMALIZED_ZERO_TO_ONE")
                || !value.path("cellWeight").asText().equals("RAW_GAZE_SAMPLE_COUNT")
                || !value.path("confidenceFiltered").isBoolean() || value.path("confidenceFiltered").booleanValue())
            throw new IllegalStateException();
        var counts = new ArrayList<Long>();
        for (var count : node.path("cellCounts")) counts.add(nonNegativeLong(count));
        var grid = new Grid(node.path("rows").intValue(), node.path("columns").intValue(), counts);
        long total = grid.cellCounts().stream().reduce(0L, Math::addExact);
        var complete = value.path("completeness");
        if (total != nonNegativeLong(complete.path("sampleCounts").path("gaze"))
                || !complete.path("unknownSessionBoundary").isBoolean()) throw new IllegalStateException();
        long gaps = nonNegativeLong(complete.path("missingSequenceRangeCount"));
        if (total == 0) {
            page.section("Observed gaze-sample distribution");
            page.row("Gaze samples", "No gaze samples were observed in this saved stream.");
        } else {
            page.heatmap(grid.cellCounts(), total, value.path("source").path("streamId").asText(),
                    gaps, complete.path("unknownSessionBoundary").booleanValue());
        }
    }

    private record PlotPoint(double x, double y, boolean breakBefore) {}

    private static void gazePlot(Layout page, JsonNode value) throws IOException {
        var nodes = value.path("points");
        int size = Math.toIntExact(nonNegativeLong(value.path("pageSize")));
        if (size < 1 || size > 500 || nonNegativeLong(value.path("sourceBatchScanLimit")) != 64
                || !nodes.isArray() || nodes.size() > size
                || !value.path("startsAtBeginning").isBoolean() || !value.path("hasNext").isBoolean())
            throw new IllegalStateException();
        boolean partial = !value.path("startsAtBeginning").booleanValue() || value.path("hasNext").booleanValue();
        var complete = value.path("completeness");
        long total = nonNegativeLong(complete.path("sampleCounts").path("gaze"));
        if (nodes.size() > total || (!partial && nodes.size() != total)
                || !complete.path("unknownSessionBoundary").isBoolean()) throw new IllegalStateException();
        var points = new ArrayList<PlotPoint>();
        long previousSequence = -1, previousIndex = -1;
        java.time.Instant previousTime = null;
        for (var node : nodes) {
            long sequence = nonNegativeLong(node.path("sequence"));
            long index = nonNegativeLong(node.path("gazeSampleIndex"));
            if (index > Integer.MAX_VALUE || !node.path("effectiveTime").isTextual()
                    || !node.path("segmentBreakBefore").isBoolean()
                    || sequence < previousSequence || (sequence == previousSequence && index <= previousIndex))
                throw new IllegalStateException();
            var time = java.time.Instant.parse(node.path("effectiveTime").textValue());
            boolean cut = node.path("segmentBreakBefore").booleanValue();
            if (previousTime != null && time.isBefore(previousTime) && !cut) throw new IllegalStateException();
            points.add(new PlotPoint(normalized(node.path("x")), normalized(node.path("y")), cut));
            previousSequence = sequence; previousIndex = index; previousTime = time;
        }
        page.gazePlot(points, value.path("source").path("streamId").asText(), partial,
                nonNegativeLong(complete.path("missingSequenceRangeCount")), complete.path("unknownSessionBoundary").booleanValue());
    }

    private static double normalized(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0 || value.doubleValue() > 1)
            throw new IllegalStateException();
        return value.doubleValue();
    }

    private static long nonNegativeLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw new IllegalStateException();
        return value.longValue();
    }

    private static void validate(JsonNode r, Snapshot s) {
        if (r == null || !r.isObject() || !r.path("schemaVersion").isIntegralNumber()
                || r.path("schemaVersion").intValue() != 1
                || !s.clinicalPatientId().toString().equals(r.path("clinicalPatientId").asText())
                || !s.therapySessionId().toString().equals(r.path("execution").path("therapySessionId").asText())
                || !s.therapySessionId().toString().equals(r.path("analytics").path("therapySessionId").asText()))
            throw new IllegalStateException();
        for (String key : List.of("doctor", "patient", "execution", "configuration", "sessionLimits", "technicalProgress", "analytics"))
            if (!r.path(key).isObject()) throw new IllegalStateException();
        var analytics = r.path("analytics");
        String availability = analytics.path("availability").asText();
        if (!analytics.path("streams").isArray()
                || !(availability.equals("CONFIRMED_NO_EVIDENCE") && analytics.path("streams").isEmpty()
                    || availability.equals("OBSERVED_EVIDENCE") && !analytics.path("streams").isEmpty()))
            throw new IllegalStateException();
        for (var stream : analytics.path("streams")) {
            for (String product : List.of("summary", "heatmap", "gazePlot")) {
                if (!s.therapySessionId().toString().equals(stream.path(product).path("source").path("therapySessionId").asText()))
                    throw new IllegalStateException();
            }
        }
    }

    private static void fields(Layout page, JsonNode node, String... keys) throws IOException {
        for (String key : keys) {
            var value = node.path(key);
            if (value.isMissingNode() || value.isContainerNode()) throw new IllegalStateException();
            page.row(key, value.isNull() ? "Not available" : value.asText());
        }
    }

    /** Only explicitly selected frozen subtrees enter the template, never the entire stored JSON. */
    private static void parameters(Layout page, JsonNode node, String label) throws IOException {
        if (node.isMissingNode()) throw new IllegalStateException();
        if (node.isObject()) {
            var entries = node.fields();
            while (entries.hasNext()) { var entry = entries.next(); parameters(page, entry.getValue(), label + "." + entry.getKey()); }
        } else if (node.isArray()) {
            if (node.isEmpty()) page.row(label, "None recorded");
            else for (int i = 0; i < node.size(); i++) parameters(page, node.get(i), label + "[" + i + "]");
        } else page.row(label, node.isNull() ? "Not available" : node.asText());
    }

    private static final class Layout implements AutoCloseable {
        private static final float LEFT = 42, RIGHT = PDRectangle.A4.getWidth() - 42;
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream content;
        private float y;
        Layout(PDDocument document, PDType0Font font) throws IOException {
            this.document = document; this.font = font; newPage();
        }
        private void newPage() throws IOException {
            if (content != null) content.close();
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            content = new PDPageContentStream(document, page);
            write("Session technical report", LEFT, 798, 16);
            write("Frozen copy | Non-diagnostic", LEFT, 781, 9);
            write("Page " + document.getNumberOfPages(), LEFT, 27, 9);
            y = 755;
        }
        private void space(float height) throws IOException { if (y - height < 48) newPage(); }
        void section(String title) throws IOException {
            for (String line : wrap(title, RIGHT - LEFT, 11)) {
                space(32); y -= 19; write(line, LEFT, y, 11); y -= 9;
            }
        }
        void heatmap(List<Long> counts, long total, String stream, long gaps, boolean unknownBoundary) throws IOException {
            long maximum = Collections.max(counts);
            var captions = new ArrayList<String>();
            for (String caption : List.of("Stream: " + stream,
                    "Scale per chart: 0 to " + maximum + " samples per cell.",
                    "Observed gaze samples: " + total + ".",
                    "Normalized coordinates: x increases rightward; y increases downward.",
                    "Relative colors are not comparable across streams or reports.",
                    "Observed data only" + (unknownBoundary ? "; session boundary unknown." : "; no complete-session guarantee."),
                    "Sequence gaps: " + gaps + ". Not fixation time or therapeutic efficacy."))
                captions.addAll(wrap(caption, RIGHT - LEFT, 9));
            // Reserve title, cells, legend and all limitations as one indivisible page block.
            float required = 28 + 12 + 280 + 16 + captions.size() * 14 + 6;
            if (required > 755 - 48) throw new IllegalStateException();
            space(required);
            section("Observed gaze-sample distribution");
            y -= 12;
            float left = (LEFT + RIGHT - 280) / 2;
            content.setStrokingColor(0.8f); content.setLineWidth(0.4f);
            for (int row = 0; row < 10; row++) for (int column = 0; column < 10; column++) {
                double intensity = counts.get(row * 10 + column) / (double) maximum;
                content.setNonStrokingColor((float) (1 - .85 * intensity), (float) (1 - .55 * intensity), (float) (1 - .15 * intensity));
                content.addRect(left + column * 28, y - (row + 1) * 28, 28, 28);
                content.fillAndStroke();
            }
            content.setNonStrokingColor(0f); content.setStrokingColor(0.85f);
            y -= 280 + 16;
            for (String line : captions) { write(line, LEFT, y, 9); y -= 14; }
            y -= 6;
        }

        void gazePlot(List<PlotPoint> points, String stream, boolean partial, long gaps, boolean unknownBoundary) throws IOException {
            var captions = new ArrayList<String>();
            var descriptions = new ArrayList<>(List.of("Stream: " + stream,
                    "Saved points shown: " + points.size() + ". Fixed-size markers; lines follow saved order.",
                    "Normalized coordinates: x increases rightward; y increases downward.",
                    partial ? "Partial saved selection; additional points are not fetched." : "Entire saved selection; not proof of a complete session.",
                    "Sequence gaps: " + gaps + ". Lines never cross marked segment breaks.",
                    unknownBoundary ? "Observed data only; session boundary unknown." : "Observed data only; no complete-session guarantee.",
                    "Not physiological fixations, saccades or therapeutic efficacy."));
            if (points.isEmpty()) descriptions.add("No points in this saved selection.");
            for (String caption : descriptions) captions.addAll(wrap(caption, RIGHT - LEFT, 9));
            float plotHeight = points.isEmpty() ? 0 : 292;
            float required = 28 + plotHeight + 16 + captions.size() * 14 + 6;
            if (required > 755 - 48) throw new IllegalStateException();
            space(required); section("Estimated gaze-point trajectory");
            if (!points.isEmpty()) {
                y -= 12; float left = (LEFT + RIGHT - 280) / 2, top = y;
                content.setStrokingColor(.8f); content.setLineWidth(.4f);
                content.addRect(left, top - 280, 280, 280); content.stroke();
                content.setStrokingColor(.15f, .45f, .85f); content.setLineWidth(.8f);
                PlotPoint previous = null;
                for (var point : points) {
                    if (previous != null && !point.breakBefore()) {
                        content.moveTo(left + (float) previous.x() * 280, top - (float) previous.y() * 280);
                        content.lineTo(left + (float) point.x() * 280, top - (float) point.y() * 280); content.stroke();
                    }
                    previous = point;
                }
                content.setNonStrokingColor(.15f, .45f, .85f);
                for (var point : points) {
                    content.addRect(left + (float) point.x() * 280 - 2, top - (float) point.y() * 280 - 2, 4, 4); content.fill();
                }
                content.setNonStrokingColor(0f); content.setStrokingColor(.85f); y -= 280;
            }
            y -= 16;
            for (String line : captions) { write(line, LEFT, y, 9); y -= 14; }
            y -= 6;
        }

        void row(String label, String value) throws IOException {
            var labels = wrap(label.replaceAll("([a-z0-9])([A-Z])", "$1 $2"), 145, 9);
            var values = wrap(value, RIGHT - LEFT - 165, 10);
            int count = Math.max(labels.size(), values.size()), offset = 0;
            while (offset < count) {
                space(18);
                int chunk = Math.min(count - offset, Math.max(1, (int) ((y - 48) / 15)));
                // Write each cell contiguously so extracted text does not interleave wrapped labels and values.
                for (int i = 0; i < chunk && offset + i < labels.size(); i++)
                    write(labels.get(offset + i), LEFT + 4, y - 15 * (i + 1), 9);
                for (int i = 0; i < chunk && offset + i < values.size(); i++)
                    write(values.get(offset + i), LEFT + 165, y - 15 * (i + 1), 10);
                y -= 15 * chunk;
                content.setStrokingColor(0.85f); content.setLineWidth(0.3f);
                content.moveTo(LEFT, y - 4); content.lineTo(RIGHT, y - 4); content.stroke();
                offset += chunk; y -= 3;
                if (offset < count) newPage();
            }
        }
        private List<String> wrap(String value, float width, float size) throws IOException {
            var lines = new ArrayList<String>(); var line = new StringBuilder();
            for (int offset = 0; offset < value.length();) {
                int cp = value.codePointAt(offset); offset += Character.charCount(cp);
                if (cp == '\n' || cp == '\r' || cp == '\t') cp = ' ';
                if (Character.isISOControl(cp)) throw new IllegalStateException();
                String glyph = new String(Character.toChars(cp));
                if (font.getStringWidth(line + glyph) / 1000 * size > width && !line.isEmpty()) {
                    int space = line.lastIndexOf(" ");
                    if (space > 0) {
                        lines.add(line.substring(0, space));
                        line.delete(0, space + 1);
                    } else { lines.add(line.toString()); line.setLength(0); }
                }
                line.append(glyph);
            }
            lines.add(line.toString()); return lines;
        }
        private void write(String value, float x, float y, float size) throws IOException {
            content.beginText(); content.setFont(font, size); content.newLineAtOffset(x, y); content.showText(value); content.endText();
        }
        @Override public void close() throws IOException { if (content != null) content.close(); }
    }
}
