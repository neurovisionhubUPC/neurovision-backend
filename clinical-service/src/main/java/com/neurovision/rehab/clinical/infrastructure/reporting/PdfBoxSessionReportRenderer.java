package com.neurovision.rehab.clinical.infrastructure.reporting;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportPdfRenderer;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
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
    public PdfBoxSessionReportRenderer(ObjectMapper mapper) {
        reader = Objects.requireNonNull(mapper).reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public byte[] render(Snapshot snapshot) {
        try {
            JsonNode report = reader.readTree(snapshot.reportJson());
            validate(report, snapshot);
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
                            fields(page, stream.path("gazePlot"), "pageSize", "sourceBatchScanLimit", "startsAtBeginning", "hasNext");
                        }
                    }
                    page.section("Interpretation limits");
                    page.row("Important", "This technical report is not a diagnosis, evidence of visual-field recovery or proof of therapeutic efficacy.");
                    page.row("Technical completion", "COMPLETED means the programmed technical objective was met; it does not guarantee complete Telemetry persistence.");
                    page.row("Missing data", "Not available and NOT_APPLICABLE do not mean zero. Results reflect only this saved copy; no services were refreshed.");
                    page.row("Analytical limits", "Sample counts are not trials or hits. Reported confidence is not a clinical quality assessment. Geometric coverage is not prescribed exploration compliance.");
                    page.row("Visualizations", "Heatmap and gaze-plot graphics are not included in this template. Observations do not establish physiological fixations or saccades.");
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
