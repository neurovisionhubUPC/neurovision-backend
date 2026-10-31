package com.neurovision.rehab.clinical.infrastructure.reporting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.Fixture;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionReportSnapshotRepository.Snapshot;
import com.neurovision.rehab.clinical.application.internal.queryservices.SessionReportV1;
import com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.*;
import com.neurovision.rehab.clinical.interfaces.rest.transform.SessionReportRestAssembler;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PdfBoxSessionReportRendererTest {
    final Fixture f = new Fixture();
    final PdfBoxSessionReportRenderer renderer = new PdfBoxSessionReportRenderer(f.mapper);
    Snapshot original() { return f.create("pdf").snapshot(); }
    Snapshot copy(Snapshot s, ObjectNode json) {
        return new Snapshot(s.id(), s.clinicalPatientId(), s.therapySessionId(), s.createdAt(), json.toString());
    }
    String text(byte[] bytes) throws Exception {
        try (var doc = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(doc); }
    }
    @Test void rendersFrozenUnicodeNamesExecutionAndAbsenceWithoutWritesOrEnrichment() throws Exception {
        f.firstName = "María Ñáñez Ω";
        var s = original(); var before = s.reportJson();
        int peers = f.peers, writes = f.writes;
        f.firstName = "Changed"; f.failPeer = "names";
        byte[] bytes = renderer.render(s);
        String text = text(bytes);
        assertTrue(text.contains("María Ñáñez Ω")); assertFalse(text.contains("Changed"));
        assertTrue(text.contains(s.id().toString())); assertTrue(text.contains("COMPLETED"));
        assertTrue(text.contains("Sin evidencia analítica disponible"));
        assertTrue(text.contains("NOT_APPLICABLE")); assertTrue(text.contains("not a diagnosis"));
        assertFalse(text.contains("synthetic-token")); assertFalse(text.contains("synthetic-doctor"));
        assertEquals(before, s.reportJson()); assertEquals(writes, f.writes); assertEquals(peers, f.peers);
        try (var doc = Loader.loadPDF(bytes)) {
            assertEquals(595.28f, doc.getPage(0).getMediaBox().getWidth(), 0.1f);
            for (var page : doc.getPages()) {
                for (var name : page.getResources().getFontNames()) assertTrue(page.getResources().getFont(name).isEmbedded());
            }
        }
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output + ".normal.pdf"), bytes);
    }
    @Test void longUnicodeNamesWrapAcrossPagesWithoutTruncationAndUnknownFieldsAreNotPrinted() throws Exception {
        var s = original(); var json = (ObjectNode) f.mapper.readTree(s.reportJson());
        String longName = "ÁéíóúÑΩ".repeat(90) + " END_OF_NAME";
        ((ObjectNode) json.get("patient")).put("firstName", longName);
        json.put("privateUnknownField", "DO_NOT_PRINT");
        byte[] bytes = renderer.render(copy(s, json));
        try (var doc = Loader.loadPDF(bytes)) {
            assertTrue(doc.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("END_OF_NAME")); assertFalse(text.contains("DO_NOT_PRINT"));
            assertEquals(longName.replaceAll("\\s", ""), text.substring(text.indexOf("ÁéíóúÑΩ"), text.indexOf("END_OF_NAME") + 11).replaceAll("\\s", ""));
        }
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output), bytes);
    }
    @Test void invalidCopiesAndUnsupportedGlyphsFailClosedWithoutRawContent() {
        var s = original();
        for (String invalid : List.of("null", "[]", "{}", "{} {}", "{\"schemaVersion\":1,\"schemaVersion\":2}",
                s.reportJson().replace("\"schemaVersion\":1", "\"schemaVersion\":999"))) {
            var failure = assertThrows(IllegalStateException.class, () -> renderer.render(new Snapshot(s.id(), s.clinicalPatientId(), s.therapySessionId(), s.createdAt(), invalid)));
            assertEquals("Session report PDF unavailable", failure.getMessage());
            assertNull(failure.getCause());
        }
        var json = (ObjectNode) assertDoesNotThrow(() -> f.mapper.readTree(s.reportJson()));
        ((ObjectNode) json.get("patient")).put("firstName", "Unsupported \uD83E\uDDE0");
        assertThrows(IllegalStateException.class, () -> renderer.render(copy(s, json)));
    }
    @Test void rendersIndependentEvidenceAndLimitationsWithoutRecalculatingOrRenderingCoordinates() throws Exception {
        var s = original(); var originalReport = f.composer().compose("synthetic-doctor", "synthetic-token", s.clinicalPatientId(), s.therapySessionId());
        var streams = new ArrayList<Stream>();
        for (int i = 0; i < 2; i++) {
            var source = new Source(s.therapySessionId(), new UUID(0, 70 + i), "baseline-v1", 9 + i, 1, 1);
            var complete = new Completeness(1, 3, 3, 2, true, List.of(new Range(2, 2)), 1, true,
                    new TimeRange(s.createdAt(), s.createdAt()), new Counts(2, 0, 2));
            var cells = new ArrayList<>(Collections.nCopies(100, 0L)); cells.set(0, 2L);
            var histogram = new ArrayList<>(Collections.nCopies(10, 0L)); histogram.set(5, 2L);
            var grid = new Grid(10, 10, cells); var empty = new Numeric(0, null, null, null);
            var ordered = new Ordered(empty, null, null, null);
            var summary = new Summary(source, complete, new Numeric(2, 0.5, 0.5, 0.5), histogram, grid,
                    new Coverage(new BoundingBox(0.1, 0.1, 0.1, 0.1, 0, 0, 0), 1, 0.01),
                    new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.Path(0, empty, 1),
                    new Gameplay(empty, ordered, ordered, ordered));
            streams.add(new Stream(summary, new Heatmap(source, complete, grid, "NORMALIZED_ZERO_TO_ONE", "RAW_GAZE_SAMPLE_COUNT", false),
                    new GazePage(source, complete, 100, 64, true, true,
                            List.of(new GazePoint(1, 0, s.createdAt(), 0.123456789, 0.987654321, 0.5, true)))));
        }
        var report = new SessionReportV1(originalReport.clinicalPatientId(), originalReport.doctor(), originalReport.patient(),
                originalReport.execution(), originalReport.configuration(), originalReport.sessionLimits(), originalReport.technicalProgress(),
                new ObservedEvidence(s.therapySessionId(), 0, 20, true, streams), originalReport.issuedAt());
        var json = (ObjectNode) SessionReportRestAssembler.toResponse(report, f.mapper).representation();
        byte[] bytes = renderer.render(copy(s, json)); String text = text(bytes).replaceAll("\\s", "");
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output + ".evidence.pdf"), bytes);
        assertTrue(text.contains("OBSERVED_EVIDENCE")); assertFalse(text.contains("Sinevidencia"));
        for (int i = 0; i < 2; i++) assertTrue(text.contains(new UUID(0, 70 + i).toString()));
        assertTrue(text.contains("unknownSessionBoundarytrue")); assertTrue(text.contains("missingSequenceRangeCount2"));
        assertTrue(text.contains("missingSequenceRangesTruncatedtrue")); assertTrue(text.contains("hasNexttrue"));
        assertTrue(text.contains("reportedconfidence.mean0.5"));
        assertTrue(text.contains("RAW_GAZE_SAMPLE_COUNT")); assertFalse(text.contains("0.123456789")); assertFalse(text.contains("0.987654321"));
        ((ObjectNode) json.path("analytics").path("streams").get(0).path("heatmap").path("source"))
                .put("therapySessionId", UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> renderer.render(copy(s, json)));
    }
}
