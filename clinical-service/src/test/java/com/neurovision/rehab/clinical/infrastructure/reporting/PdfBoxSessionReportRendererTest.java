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
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
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
    private int visualCopyNumber;
    private Snapshot visualCopy(String origin, String median, long reported, long hits, long omissions, long interrupted) {
        f.visualSearch = true;
        var selected = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Origin.valueOf(origin);
        f.visualSummary = new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportVisualSearchPort.Summary(
                "visual-search-trial-v1", new UUID(0, 2), new UUID(0, 4), 1, selected,
                reported, hits, omissions, interrupted, hits, median == null ? null : new java.math.BigDecimal(median));
        return f.service().create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2),
                "visual-pdf-" + ++visualCopyNumber, selected).snapshot();
    }
    private static final String EXPLORATION_TABLE = "Systematic Exploration - reported final results";
    private Snapshot explorationCopy(String origin, int count) {
        f.exploration = true;
        var selected = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Origin.valueOf(origin);
        var rows = new ArrayList<com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Traversal>();
        for (int i = 0; i < count; i++) {
            int regions = i % 3 == 0 ? 4 : i % 3 == 1 ? 2 : 1;
            var outcome = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Outcome.valueOf(
                    regions == 4 ? "COMPLETED" : regions == 2 ? "TIMED_OUT" : "INTERRUPTED");
            rows.add(new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Traversal(
                    String.format(Locale.ROOT, "traversal-%04d", i), outcome, regions, regions * 25, regions == 4 ? 4000 : regions == 2 ? 5000 : 1500));
        }
        f.explorationSummary = new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSystematicExplorationPort.Summary(
                "systematic-exploration-trial-v1", new UUID(0, 2), new UUID(0, 4), 1, selected, count, rows);
        return f.service().create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2),
                "exploration-pdf-" + origin + "-" + count, null, null, null, null, selected).snapshot();
    }

    @Test void rendersOnlyFrozenExplorationRowsAndProvenance() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            var saved = explorationCopy(origin, 3); String frozen = saved.reportJson();
            int peers = f.peers, writes = f.writes, observed = f.explorationCalls;
            f.failPeer = "names";
            byte[] bytes = renderer.render(saved); String flat = compact(text(bytes));
            for (String value : List.of(EXPLORATION_TABLE, "Data origin" + origin,
                    "Calculation version systematic-exploration-trial-v1", "Reported traversals3",
                    "Traversal reference traversal-0000", "Outcome COMPLETED", "Visited regions4 of 4", "Coverage (%)100", "Elapsed duration (ms)4000",
                    "Traversal reference traversal-0001", "Outcome TIMED_OUT", "Visited regions2 of 4", "Coverage (%)50", "Elapsed duration (ms)5000",
                    "Traversal reference traversal-0002", "Outcome INTERRUPTED", "Visited regions1 of 4", "Coverage (%)25", "Elapsed duration (ms)1500"))
                assertTrue(flat.contains(compact(value)), "Frozen exploration value missing");
            assertTrue(flat.indexOf("traversal-0000") < flat.indexOf("traversal-0001"));
            assertTrue(flat.indexOf("traversal-0001") < flat.indexOf("traversal-0002"));
            assertTrue(flat.contains(origin.equals("SYNTHETIC") ? "testdata,notpatientevidence" : "notalgorithmicallyverifiedclinicalsuccess"));
            assertTrue(flat.contains("notproofofclinicalefficacy"));
            assertEquals(frozen, saved.reportJson()); assertEquals(peers, f.peers);
            assertEquals(writes, f.writes); assertEquals(observed, f.explorationCalls);
            exportVisual(".systematic-exploration-" + origin.toLowerCase(Locale.ROOT), bytes); f.failPeer = null;
        }
    }

    @Test void explorationEmptyAndAbsentRemainDifferent() throws Exception {
        byte[] bytes = renderer.render(explorationCopy("SYNTHETIC", 0)); String flat = compact(text(bytes));
        assertTrue(flat.contains(compact(EXPLORATION_TABLE))); assertTrue(flat.contains("Reportedtraversals0"));
        assertTrue(flat.contains("Noreportedtraversals")); assertFalse(flat.contains("Elapsedduration"));
        f.exploration = false; assertFalse(text(renderer.render(original())).contains(EXPLORATION_TABLE));
        exportVisual(".systematic-exploration-empty", bytes);
    }

    @Test void explorationMalformedPresentSectionFailsWithoutChangingCopy() throws Exception {
        var saved = explorationCopy("SYNTHETIC", 3); var baseline = (ObjectNode) f.mapper.readTree(saved.reportJson());
        for (String fault : List.of("null", "count", "coverage", "origin", "binding", "overflow")) {
            var changed = baseline.deepCopy(); var section = (ObjectNode) changed.get("systematicExploration");
            switch (fault) {
                case "null" -> changed.putNull("systematicExploration");
                case "count" -> section.put("reportedTrialCount", 0);
                case "coverage" -> ((ObjectNode) section.withArray("traversals").get(0)).put("coveragePercent", 99);
                case "origin" -> section.put("dataOrigin", "UNKNOWN");
                case "binding" -> section.put("therapySessionId", new UUID(0, 99).toString());
                case "overflow" -> section.put("reportedTrialCount", 1001);
            }
            var invalid = copy(saved, changed); String frozen = invalid.reportJson();
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(invalid));
            assertEquals("Session report PDF unavailable", error.getMessage()); assertNull(error.getCause());
            assertEquals(frozen, invalid.reportJson());
        }
    }

    @Test void explorationMaximumTraversalsContinueAcrossPagesWithoutTruncation() throws Exception {
        var saved = explorationCopy("SYNTHETIC", 1000); String frozen = saved.reportJson();
        byte[] bytes = renderer.render(saved); String extracted = text(bytes);
        assertEquals(1000, extracted.split("Traversal reference", -1).length - 1);
        assertTrue(extracted.contains("traversal-0999"));
        try (var doc = Loader.loadPDF(bytes)) { assertTrue(doc.getNumberOfPages() > 1); }
        assertEquals(frozen, saved.reportJson());
    }

    private static final String FIXATION_TABLE = "Sustained Fixation - reported final results";

    private Snapshot fixationCopy(String origin, long attempts, long insideTargetMs, long completed) {
        var selected = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Origin.valueOf(origin);
        f.fixationSummary = new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportSustainedFixationPort.Summary(
                "sustained-fixation-trial-v1", new UUID(0, 2), new UUID(0, 4), 1, selected,
                attempts, insideTargetMs, completed);
        return f.service().create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2),
                "fixation-pdf-" + origin + "-" + attempts, null, null, selected).snapshot();
    }

    @Test void rendersFrozenSustainedFixationRowsAndSourceLimitsTogether() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            var saved = fixationCopy(origin, 3, 4201, 2);
            String frozen = saved.reportJson();
            int peers = f.peers, writes = f.writes;
            f.failPeer = "names";
            byte[] bytes = renderer.render(saved);
            assertTrue(text(bytes).contains("Sin evidencia analítica disponible"));
            try (var doc = Loader.loadPDF(bytes)) {
                int tables = 0;
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    var stripper = new PDFTextStripper();
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String page = stripper.getText(doc);
                    if (!page.contains(FIXATION_TABLE)) continue;
                    tables++;
                    String flat = compact(page);
                    for (String row : List.of("Dataorigin" + origin, "Calculationversionsustained-fixation-trial-v1",
                            "Reportedattempts3", "Totalvalidtimeinsidethetarget(ms)4201", "Completedtargets2"))
                        assertTrue(flat.contains(row), "Saved Sustained Fixation row missing from its table page");
                    assertTrue(flat.contains("notcontinuousdwellorphysiologicalfixation"));
                    assertTrue(flat.contains(origin.equals("SYNTHETIC") ? "testdata,notpatientevidence"
                            : "notalgorithmicallyverifiedclinicalsuccess"));
                }
                assertEquals(1, tables);
            }
            assertEquals(frozen, saved.reportJson());
            assertEquals(peers, f.peers);
            assertEquals(writes, f.writes);
            exportVisual(".sustained-fixation-" + origin.toLowerCase(Locale.ROOT), bytes);
            f.failPeer = null;
        }
    }

    @Test void sustainedFixationConfirmedZeroIsNotAnOmittedSection() throws Exception {
        String zero = compact(text(renderer.render(fixationCopy("SYNTHETIC", 0, 0, 0))));
        assertTrue(zero.contains(compact(FIXATION_TABLE)));
        assertTrue(zero.contains("Reportedattempts0"));
        assertTrue(zero.contains("Totalvalidtimeinsidethetarget(ms)0"));
        assertTrue(zero.contains("Completedtargets0"));
        assertFalse(text(renderer.render(original())).contains(FIXATION_TABLE));
    }

    @Test void malformedOrMismatchedSustainedFixationFailsWithoutAPartialPdf() throws Exception {
        var saved = fixationCopy("SYNTHETIC", 3, 4201, 2);
        int peers = f.peers, writes = f.writes;
        for (int kind = 0; kind < 11; kind++) {
            var report = (ObjectNode) f.mapper.readTree(saved.reportJson());
            var summary = (ObjectNode) report.path("sustainedFixation");
            switch (kind) {
                case 0 -> report.putNull("sustainedFixation");
                case 1 -> summary.remove("totalInsideTargetMs");
                case 2 -> summary.put("totalInsideTargetMs", 4201.5);
                case 3 -> summary.put("completedTargetCount", 4);
                case 4 -> summary.put("dataOrigin", "UNKNOWN");
                case 5 -> summary.put("calculationVersion", "unknown");
                case 6 -> summary.put("therapySessionId", new UUID(0, 99).toString());
                case 7 -> summary.put("configurationId", new UUID(0, 99).toString());
                case 8 -> summary.put("configurationVersion", 2);
                case 9 -> report.put("mode", "STANDARD_SCANNING");
                case 10 -> report.set("standardScanning", summary.deepCopy());
            }
            var invalid = copy(saved, report);
            String frozen = invalid.reportJson();
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(invalid));
            assertEquals("Session report PDF unavailable", error.getMessage());
            assertNull(error.getCause());
            assertEquals(frozen, invalid.reportJson());
        }
        assertEquals(peers, f.peers);
        assertEquals(writes, f.writes);
    }


    private static final String SACCADE_TABLE = "Compensatory Saccade - reported final results";

    private Snapshot saccadeCopy(String origin, long reported, long reached, String median) {
        var selected = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Origin.valueOf(origin);
        f.saccade = true;
        f.saccadeSummary = new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportCompensatorySaccadePort.Summary(
                "compensatory-saccade-trial-v1", new UUID(0, 2), new UUID(0, 4), 1, selected,
                reported, reached, median == null ? null : new java.math.BigDecimal(median));
        return f.service().create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2),
                "saccade-pdf-" + origin + "-" + reported + "-" + reached, null, null, null, selected).snapshot();
    }

    @Test void rendersFrozenCompensatorySaccadeRowsAndSourceLimitsTogether() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            var saved = saccadeCopy(origin, 3, 2, "1250.5");
            String frozen = saved.reportJson();
            int peers = f.peers, writes = f.writes;
            f.failPeer = "saccade";
            try {
                byte[] bytes = renderer.render(saved);
                assertTrue(text(bytes).contains("Sin evidencia anal\u00edtica disponible"));
                try (var doc = Loader.loadPDF(bytes)) {
                    int tables = 0;
                    for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                        var stripper = new PDFTextStripper();
                        stripper.setStartPage(i);
                        stripper.setEndPage(i);
                        String page = stripper.getText(doc);
                        if (!page.contains(SACCADE_TABLE)) continue;
                        tables++;
                        String flat = compact(page);
                        for (String row : List.of("Dataorigin" + origin, "Calculationversioncompensatory-saccade-trial-v1",
                                "Reportedattempts3", "Reachedtargets2", "Mediantimetotarget(ms)1250.5",
                                "Successfulattemptsonly;includesconfirmationdwell"))
                            assertTrue(flat.contains(row), "Saved Compensatory Saccade value missing from its table page");
                        assertTrue(flat.contains(origin.equals("SYNTHETIC") ? "testdata,notpatientevidence"
                                : "notalgorithmicallyverifiedclinicalsuccess"));
                    }
                    assertEquals(1, tables);
                }
                assertEquals(frozen, saved.reportJson());
                assertEquals(peers, f.peers);
                assertEquals(writes, f.writes);
            } finally {
                f.failPeer = null;
            }
        }
    }

    @Test void compensatorySaccadeZeroAndNullRemainDistinctFromOmission() throws Exception {
        for (long reported : List.of(0L, 3L)) {
            String shown = compact(text(renderer.render(saccadeCopy("SYNTHETIC", reported, 0, null))));
            assertTrue(shown.contains(compact(SACCADE_TABLE)));
            assertTrue(shown.contains("Reportedattempts" + reported));
            assertTrue(shown.contains("Reachedtargets0"));
            assertTrue(shown.contains("Mediantimetotarget(ms)Notavailable"));
        }
        String omitted = compact(text(renderer.render(original())));
        assertFalse(omitted.contains(compact(SACCADE_TABLE)));
        assertFalse(omitted.contains("Mediantimetotarget(ms)"));
    }

    @Test void invalidCompensatorySaccadeFailsWithoutAPartialPdf() throws Exception {
        var saved = saccadeCopy("SYNTHETIC", 3, 2, "1250.5");
        int peers = f.peers, writes = f.writes;
        for (int kind = 0; kind < 4; kind++) {
            var report = (ObjectNode) f.mapper.readTree(saved.reportJson());
            var summary = (ObjectNode) report.path("compensatorySaccade");
            switch (kind) {
                case 0 -> report.putNull("compensatorySaccade");
                case 1 -> summary.putNull("medianTimeToTargetMs");
                case 2 -> summary.put("configurationVersion", 2);
                case 3 -> report.put("mode", "STANDARD_SCANNING");
            }
            var invalid = copy(saved, report);
            String frozen = invalid.reportJson();
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(invalid));
            assertEquals("Session report PDF unavailable", error.getMessage());
            assertNull(error.getCause());
            assertEquals(frozen, invalid.reportJson());
        }
        assertEquals(peers, f.peers);
        assertEquals(writes, f.writes);
    }

    private int standardCopyNumber;
    private Snapshot standardCopy(String origin, String median, long reported, long detected, long omissions, long interrupted) {
        f.standardScanning = true;
        f.visualSearch = false;
        var selected = com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Origin.valueOf(origin);
        f.scanningSummary = new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportStandardScanningPort.Summary(
                "standard-scanning-trial-v1", new UUID(0, 2), new UUID(0, 4), 1, selected,
                reported, detected, omissions, interrupted, detected, median == null ? null : new java.math.BigDecimal(median));
        return f.service().create("synthetic-doctor", "synthetic-token", new UUID(0, 1), new UUID(0, 2),
                "standard-pdf-" + ++standardCopyNumber, null, selected).snapshot();
    }

    @Test void rendersBothFrozenStandardScanningOriginsMetricsAndSourceLimitationsTogether() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            var saved = standardCopy(origin, "1250.5", 6, 3, 2, 1); String frozen = saved.reportJson();
            int peers = f.peers, writes = f.writes; f.failPeer = "names";
            byte[] bytes = renderer.render(saved); String all = text(bytes);
            assertTrue(all.contains("Standard Scanning - reported final results"));
            assertTrue(all.contains("Sin evidencia analítica disponible"), "Baseline absence remains independent of trial results");
            try (var doc = Loader.loadPDF(bytes)) {
                boolean found = false;
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    var stripper = new PDFTextStripper(); stripper.setStartPage(i); stripper.setEndPage(i);
                    String page = stripper.getText(doc);
                    if (!page.contains("Standard Scanning - reported final results")) continue;
                    String flat = compact(page); found = true;
                    for (String value : List.of("Dataorigin" + origin, "Calculationversionstandard-scanning-trial-v1", "Reportedattempts6",
                            "Detectedtargets3", "Omissions2", "Interruptions1", "Eligiblelocalizations3", "Medianlocalizationduration(ms)1250.5"))
                        assertTrue(flat.contains(value), "Saved Standard Scanning table value missing from its section page");
                    assertTrue(flat.contains(origin.equals("SYNTHETIC") ? "testdata,notpatientevidence" : "notalgorithmicallyverifiedclinicalsuccess"));
                    assertTrue(flat.contains("nottotalpresentationsoracompletenessguarantee"));
                }
                assertTrue(found, "Standard Scanning table and limitations must remain on one page");
            }
            assertEquals(frozen, saved.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
            exportVisual(origin.equals("SYNTHETIC") ? ".standard-scanning-synthetic" : ".standard-scanning-producer", bytes);
            f.failPeer = null;
        }
    }

    @Test void standardScanningConfirmedZeroNoDetectionsAndLegacyOmissionStayDistinct() throws Exception {
        var zero = standardCopy("SYNTHETIC", null, 0, 0, 0, 0); byte[] zeroBytes = renderer.render(zero);
        String zeroText = compact(text(zeroBytes));
        assertTrue(zeroText.contains("Reportedattempts0")); assertTrue(zeroText.contains("Detectedtargets0"));
        assertTrue(zeroText.contains("Medianlocalizationduration(ms)Notavailable"));
        assertFalse(zeroText.contains("StandardScanningresultsnotincluded"));
        var noDetections = standardCopy("PRODUCER_REPORTED", null, 3, 0, 2, 1); byte[] noHitBytes = renderer.render(noDetections);
        String noHitText = compact(text(noHitBytes));
        assertTrue(noHitText.contains("Reportedattempts3")); assertTrue(noHitText.contains("Omissions2"));
        assertTrue(noHitText.contains("Interruptions1")); assertTrue(noHitText.contains("Medianlocalizationduration(ms)Notavailable"));
        var legacy = original(); String frozen = legacy.reportJson(); int peers = f.peers, writes = f.writes;
        byte[] legacyBytes = renderer.render(legacy); String legacyText = compact(text(legacyBytes));
        assertFalse(legacyText.contains("StandardScanning-reportedfinalresults")); assertFalse(legacyText.contains("Reportedattempts"));
        assertEquals(frozen, legacy.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        exportVisual(".standard-scanning-zero", zeroBytes); exportVisual(".standard-scanning-no-hits", noHitBytes); exportVisual(".standard-scanning-legacy", legacyBytes);
    }

    @Test void standardScanningSavedMedianUsesExactPlainDecimalWithoutDoubleRounding() throws Exception {
        for (String median : List.of("1250", "1250.5", "9223372036854775806.5", "9223372036854775807")) {
            var saved = standardCopy("PRODUCER_REPORTED", median, 1, 1, 0, 0);
            String result = compact(text(renderer.render(saved)));
            assertTrue(result.contains("Medianlocalizationduration(ms)" + median), "Saved median must remain an exact plain decimal");
            assertFalse(result.contains("E+"));
        }
    }

    @Test void malformedPresentStandardScanningFailsClosedWithoutMutationOrEnrichment() throws Exception {
        var saved = standardCopy("SYNTHETIC", "1250.5", 6, 3, 2, 1);
        for (int kind = 0; kind < 22; kind++) {
            var json = (ObjectNode) f.mapper.readTree(saved.reportJson()); var summary = (ObjectNode) json.path("standardScanning");
            switch (kind) {
                case 21 -> json.set("visualSearch", summary.deepCopy());
                case 0 -> json.putNull("standardScanning");
                case 1 -> json.put("standardScanning", "invalid");
                case 2 -> json.set("standardScanning", f.mapper.createObjectNode());
                case 3 -> summary.put("extra", 1);
                case 4 -> summary.put("calculationVersion", "unknown");
                case 5 -> summary.put("dataOrigin", "UNKNOWN");
                case 6 -> summary.put("reportedTrialCount", -1);
                case 7 -> summary.put("reportedTrialCount", 7);
                case 8 -> summary.put("eligibleLocalizationCount", 2);
                case 9 -> summary.put("detectedTargetCount", 3.5);
                case 10 -> summary.put("medianLocalizationDurationMs", "1250.5");
                case 11 -> summary.putNull("medianLocalizationDurationMs");
                case 12 -> summary.put("medianLocalizationDurationMs", -1);
                case 13 -> summary.put("medianLocalizationDurationMs", 1.25);
                case 14 -> summary.put("medianLocalizationDurationMs", new java.math.BigDecimal("9223372036854775808"));
                case 15 -> summary.put("therapySessionId", new UUID(0, 99).toString());
                case 16 -> summary.put("configurationId", new UUID(0, 99).toString());
                case 17 -> summary.put("configurationVersion", 2);
                case 18 -> json.put("mode", "SUSTAINED_TARGET_FIXATION");
                case 19 -> ((ObjectNode) json.path("configuration")).put("configurationVersion", 2);
                case 20 -> summary.remove("dataOrigin");
            }
            var bad = copy(saved, json); String frozen = bad.reportJson(); int peers = f.peers, writes = f.writes;
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(bad));
            assertEquals("Session report PDF unavailable", error.getMessage()); assertNull(error.getCause());
            assertEquals(frozen, bad.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        }
    }

    @Test void standardScanningTableLeavesSavedHeatmapAndGazeGeometryUnchanged() throws Exception {
        f.standardScanning = true;
        var saved = gazeCopy(true, true, 1); byte[] without = renderer.render(saved);
        var selected = standardCopy("SYNTHETIC", "1250.5", 6, 3, 2, 1);
        var json = (ObjectNode) f.mapper.readTree(saved.reportJson());
        json.set("standardScanning", f.mapper.readTree(selected.reportJson()).get("standardScanning"));
        var combined = copy(saved, json); String frozen = combined.reportJson(); int peers = f.peers, writes = f.writes;
        byte[] bytes = renderer.render(combined);
        try (var before = Loader.loadPDF(without); var after = Loader.loadPDF(bytes)) {
            assertEquals(gridCells(before), gridCells(after)); assertEquals(gazeMarks(before), gazeMarks(after));
            assertEquals(gazeLines(before), gazeLines(after));
            assertEquals(100, gridCells(after).size()); assertEquals(4, gazeMarks(after).size()); assertEquals(2, gazeLines(after).size());
        }
        assertTrue(text(bytes).contains("Standard Scanning - reported final results"));
        assertEquals(frozen, combined.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        exportVisual(".standard-scanning-combined", bytes);
    }

    private String compact(String value) { return value.replaceAll("\\s", ""); }
    private void exportVisual(String suffix, byte[] bytes) throws Exception {
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output == null) return;
        Files.write(java.nio.file.Path.of(output + suffix + ".pdf"), bytes);
        try (var doc = Loader.loadPDF(bytes)) {
            var raster = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                var stripper = new PDFTextStripper(); stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
                String page = stripper.getText(doc);
                if (page.contains("Systematic Exploration") || page.contains("Traversal reference") || page.contains("Standard Scanning") || page.contains("Visual Search") || page.contains("Observed gaze-sample distribution") || page.contains("Estimated gaze-point trajectory"))
                    javax.imageio.ImageIO.write(raster.renderImageWithDPI(i, 110, ImageType.RGB), "png", java.nio.file.Path.of(output + suffix + "-page-" + (i + 1) + ".png").toFile());
            }
        }
    }

    @Test void rendersBothFrozenVisualSearchOriginsMetricsAndSourceLimitationsTogether() throws Exception {
        for (String origin : List.of("SYNTHETIC", "PRODUCER_REPORTED")) {
            var saved = visualCopy(origin, "1250.5", 6, 3, 2, 1); String frozen = saved.reportJson();
            int peers = f.peers, writes = f.writes; f.failPeer = "names";
            byte[] bytes = renderer.render(saved); String all = text(bytes);
            assertTrue(all.contains("Visual Search - reported final results"));
            assertTrue(all.contains("Sin evidencia analítica disponible"), "Baseline absence remains independent of trial results");
            try (var doc = Loader.loadPDF(bytes)) {
                boolean found = false;
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    var stripper = new PDFTextStripper(); stripper.setStartPage(i); stripper.setEndPage(i);
                    String page = stripper.getText(doc);
                    if (!page.contains("Visual Search - reported final results")) continue;
                    String flat = compact(page); found = true;
                    for (String value : List.of("Dataorigin" + origin, "Calculationversionvisual-search-trial-v1", "Reportedattempts6",
                            "Hits3", "Omissions2", "Interruptions1", "Eligiblesearches3", "Mediansearchduration(ms)1250.5"))
                        assertTrue(flat.contains(value), "Saved Visual Search table value missing from its section page");
                    assertTrue(flat.contains(origin.equals("SYNTHETIC") ? "testdata,notpatientevidence" : "notalgorithmicallyverifiedclinicalsuccess"));
                    assertTrue(flat.contains("nottotalpresentationsoracompletenessguarantee"));
                }
                assertTrue(found, "Visual Search table and limitations must remain on one page");
            }
            assertEquals(frozen, saved.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
            exportVisual(origin.equals("SYNTHETIC") ? ".visual-search-synthetic" : ".visual-search-producer", bytes);
            f.failPeer = null;
        }
    }

    @Test void confirmedZeroNoSuccessfulSearchAndLegacyOmissionStayDistinct() throws Exception {
        var zero = visualCopy("SYNTHETIC", null, 0, 0, 0, 0); byte[] zeroBytes = renderer.render(zero);
        String zeroText = compact(text(zeroBytes));
        assertTrue(zeroText.contains("Reportedattempts0")); assertTrue(zeroText.contains("Hits0"));
        assertTrue(zeroText.contains("Mediansearchduration(ms)Notavailable"));
        assertFalse(zeroText.contains("VisualSearchresultsnotincluded"));
        var noHits = visualCopy("PRODUCER_REPORTED", null, 3, 0, 2, 1); byte[] noHitBytes = renderer.render(noHits);
        String noHitText = compact(text(noHitBytes));
        assertTrue(noHitText.contains("Reportedattempts3")); assertTrue(noHitText.contains("Omissions2"));
        assertTrue(noHitText.contains("Interruptions1")); assertTrue(noHitText.contains("Mediansearchduration(ms)Notavailable"));
        var legacy = original(); String frozen = legacy.reportJson(); int peers = f.peers, writes = f.writes;
        byte[] legacyBytes = renderer.render(legacy); String legacyText = compact(text(legacyBytes));
        assertTrue(legacyText.contains("VisualSearchresultsnotincluded")); assertFalse(legacyText.contains("Reportedattempts"));
        assertEquals(frozen, legacy.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        exportVisual(".visual-search-zero", zeroBytes); exportVisual(".visual-search-no-hits", noHitBytes); exportVisual(".visual-search-legacy", legacyBytes);
    }

    @Test void savedMedianUsesExactPlainDecimalWithoutDoubleRounding() throws Exception {
        for (String median : List.of("1250", "1250.5", "9223372036854775806.5", "9223372036854775807")) {
            var saved = visualCopy("PRODUCER_REPORTED", median, 1, 1, 0, 0);
            String result = compact(text(renderer.render(saved)));
            assertTrue(result.contains("Mediansearchduration(ms)" + median), "Saved median must remain an exact plain decimal");
            assertFalse(result.contains("E+"));
        }
    }

    @Test void malformedPresentVisualSearchFailsClosedWithoutMutationOrEnrichment() throws Exception {
        var saved = visualCopy("SYNTHETIC", "1250.5", 6, 3, 2, 1);
        for (int kind = 0; kind < 21; kind++) {
            var json = (ObjectNode) f.mapper.readTree(saved.reportJson()); var summary = (ObjectNode) json.path("visualSearch");
            switch (kind) {
                case 0 -> json.putNull("visualSearch");
                case 1 -> json.put("visualSearch", "invalid");
                case 2 -> json.set("visualSearch", f.mapper.createObjectNode());
                case 3 -> summary.put("extra", 1);
                case 4 -> summary.put("calculationVersion", "unknown");
                case 5 -> summary.put("dataOrigin", "UNKNOWN");
                case 6 -> summary.put("reportedTrialCount", -1);
                case 7 -> summary.put("reportedTrialCount", 7);
                case 8 -> summary.put("eligibleSearchCount", 2);
                case 9 -> summary.put("hitCount", 3.5);
                case 10 -> summary.put("medianSearchDurationMs", "1250.5");
                case 11 -> summary.putNull("medianSearchDurationMs");
                case 12 -> summary.put("medianSearchDurationMs", -1);
                case 13 -> summary.put("medianSearchDurationMs", 1.25);
                case 14 -> summary.put("medianSearchDurationMs", new java.math.BigDecimal("9223372036854775808"));
                case 15 -> summary.put("therapySessionId", new UUID(0, 99).toString());
                case 16 -> summary.put("configurationId", new UUID(0, 99).toString());
                case 17 -> summary.put("configurationVersion", 2);
                case 18 -> json.put("mode", "SUSTAINED_TARGET_FIXATION");
                case 19 -> ((ObjectNode) json.path("configuration")).put("configurationVersion", 2);
                case 20 -> summary.remove("dataOrigin");
            }
            var bad = copy(saved, json); String frozen = bad.reportJson(); int peers = f.peers, writes = f.writes;
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(bad));
            assertEquals("Session report PDF unavailable", error.getMessage()); assertNull(error.getCause());
            assertEquals(frozen, bad.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        }
    }

    @Test void visualSearchTableLeavesSavedHeatmapAndGazeGeometryUnchanged() throws Exception {
        f.visualSearch = true;
        var saved = gazeCopy(true, true, 1); byte[] without = renderer.render(saved);
        var selected = visualCopy("SYNTHETIC", "1250.5", 6, 3, 2, 1);
        var json = (ObjectNode) f.mapper.readTree(saved.reportJson());
        json.set("visualSearch", f.mapper.readTree(selected.reportJson()).get("visualSearch"));
        var combined = copy(saved, json); String frozen = combined.reportJson(); int peers = f.peers, writes = f.writes;
        byte[] bytes = renderer.render(combined);
        try (var before = Loader.loadPDF(without); var after = Loader.loadPDF(bytes)) {
            assertEquals(gridCells(before), gridCells(after)); assertEquals(gazeMarks(before), gazeMarks(after));
            assertEquals(gazeLines(before), gazeLines(after));
            assertEquals(100, gridCells(after).size()); assertEquals(4, gazeMarks(after).size()); assertEquals(2, gazeLines(after).size());
        }
        assertTrue(text(bytes).contains("Visual Search - reported final results"));
        assertEquals(frozen, combined.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        exportVisual(".visual-search-combined", bytes);
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
        assertFalse(text.contains("Observed gaze-sample distribution"));
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
    @Test void drawsFrozenCellsInStoredOrderWithAnIndependentScalePerStream() throws Exception {
        var cells = new long[100]; cells[0] = 4; cells[9] = 2; cells[90] = 1; cells[99] = 3;
        var scaled = cells.clone(); for (int i = 0; i < scaled.length; i++) scaled[i] *= 2;
        var saved = observed(cells, scaled); String before = saved.reportJson();
        int peers = f.peers, writes = f.writes; f.failPeer = "names";
        byte[] bytes = renderer.render(saved);
        try (var doc = Loader.loadPDF(bytes)) {
            var drawn = gridCells(doc); assertEquals(200, drawn.size());
            var raster = new PDFRenderer(doc);
            for (int stream = 0; stream < 2; stream++) {
                var first = drawn.get(stream * 100);
                var image = raster.renderImageWithDPI(first.page(), 72, ImageType.RGB);
                for (int i = 0; i < 100; i++) {
                    var cell = drawn.get(stream * 100 + i);
                    assertEquals(first.page(), cell.page(), "A grid must stay on one page");
                    assertEquals(first.x() + (i % 10) * 28, cell.x(), 0.02);
                    assertEquals(first.y() - (i / 10) * 28, cell.y(), 0.02);
                    int pixel = image.getRGB(Math.round(cell.x() + 14),
                            Math.round(doc.getPage(cell.page()).getMediaBox().getHeight() - cell.y() - 14));
                    double intensity = cells[i] / 4.0;
                    int[] expected = {(int) Math.round(255 * (1 - .85 * intensity)),
                            (int) Math.round(255 * (1 - .55 * intensity)), (int) Math.round(255 * (1 - .15 * intensity))};
                    for (int channel = 0; channel < 3; channel++)
                        assertEquals(expected[channel], (pixel >> (16 - 8 * channel)) & 255, 2);
                }
                var stripper = new PDFTextStripper(); stripper.setStartPage(first.page() + 1); stripper.setEndPage(first.page() + 1);
                String pageText = stripper.getText(doc);
                assertTrue(pageText.contains("Observed gaze-sample distribution"));
                assertTrue(pageText.contains("Scale per chart: 0 to " + (stream == 0 ? 4 : 8)));
                assertTrue(pageText.contains("Observed gaze samples: " + (stream == 0 ? 10 : 20)));
                assertTrue(pageText.contains("session boundary unknown"));
                assertTrue(pageText.contains("Sequence gaps: 2"));
            }
        }
        assertEquals(before, saved.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output + ".heatmap.pdf"), bytes);
    }

    @Test void zeroGazeIsNotMissingEvidenceAndDoesNotProduceAFabricatedGrid() throws Exception {
        var saved = observed(new long[100]); byte[] bytes = renderer.render(saved);
        assertTrue(text(bytes).contains("No gaze samples were observed in this saved stream."));
        assertFalse(text(bytes).contains("Sin evidencia analítica disponible"));
        try (var doc = Loader.loadPDF(bytes)) { assertTrue(gridCells(doc).isEmpty()); }
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output + ".zero.pdf"), bytes);
    }

    @Test void malformedFrozenGridsFailClosedWithoutChangingTheCopyOrQueryingPeers() throws Exception {
        var cells = new long[100]; cells[0] = 2; var saved = observed(cells);
        for (int kind = 0; kind < 9; kind++) {
            var json = (ObjectNode) f.mapper.readTree(saved.reportJson());
            var heatmap = (ObjectNode) json.path("analytics").path("streams").get(0).path("heatmap");
            var grid = (ObjectNode) heatmap.path("grid");
            var counts = (com.fasterxml.jackson.databind.node.ArrayNode) grid.path("cellCounts");
            switch (kind) {
                case 0 -> grid.put("rows", 9);
                case 1 -> counts.remove(99);
                case 2 -> counts.set(0, f.mapper.getNodeFactory().numberNode(-1));
                case 3 -> counts.set(0, f.mapper.getNodeFactory().numberNode(1.5));
                case 4 -> counts.set(0, f.mapper.getNodeFactory().textNode("2"));
                case 5 -> counts.set(0, f.mapper.getNodeFactory().numberNode(1));
                case 6 -> { counts.set(0, f.mapper.getNodeFactory().numberNode(Long.MAX_VALUE)); counts.set(1, f.mapper.getNodeFactory().numberNode(1)); }
                case 7 -> heatmap.put("coordinateSpace", "UNSUPPORTED");
                case 8 -> heatmap.put("confidenceFiltered", true);
            }
            var corrupt = copy(saved, json); String before = corrupt.reportJson(); int peers = f.peers, writes = f.writes;
            var failure = assertThrows(IllegalStateException.class, () -> renderer.render(corrupt));
            assertEquals("Session report PDF unavailable", failure.getMessage()); assertNull(failure.getCause());
            assertEquals(before, corrupt.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        }
    }

    @Test void gazePlotUsesSavedOrderAndBreaksWithoutJoiningStreamsOrChangingEvidence() throws Exception {
        var s = gazeCopy(true, true, 2);
        String before = s.reportJson(); int peers = f.peers, writes = f.writes;
        byte[] bytes = renderer.render(s);
        try (var doc = Loader.loadPDF(bytes)) {
            var marks = gazeMarks(doc); var lines = gazeLines(doc);
            assertEquals(8, marks.size()); assertEquals(4, lines.size());
            for (int stream = 0; stream < 2; stream++) {
                var first = marks.get(stream * 4); var second = marks.get(stream * 4 + 1);
                assertEquals(84, second.x() - first.x(), .02);
                assertEquals(-84, second.y() - first.y(), .02);
                var a = lines.get(stream * 2); var b = lines.get(stream * 2 + 1);
                assertEquals(first.page(), a.page()); assertEquals(first.x() + 2, a.x1(), .02);
                assertEquals(first.y() + 2, a.y1(), .02);
                assertEquals(second.x() + 2, a.x2(), .02); assertEquals(second.y() + 2, a.y2(), .02);
                assertEquals(marks.get(stream * 4 + 2).x() + 2, b.x1(), .02);
                assertEquals(marks.get(stream * 4 + 3).x() + 2, b.x2(), .02);
                String pageText = new PDFTextStripper() {{ setStartPage(first.page() + 1); setEndPage(first.page() + 1); }}.getText(doc);
                assertTrue(pageText.contains("Estimated gaze-point trajectory"));
                assertTrue(pageText.contains("Partial saved selection"));
                assertTrue(pageText.contains("session boundary unknown"));
            }
        }
        assertEquals(before, s.reportJson()); assertEquals(peers, f.peers); assertEquals(writes, f.writes);
        String output = System.getProperty("clinical.pdf.visual-output");
        if (output != null) Files.write(java.nio.file.Path.of(output + ".gaze.pdf"), bytes);
    }

    @Test void emptyAndSinglePointSelectionsNeverInventAbsenceOrAPath() throws Exception {
        var s = gazeCopy(false, false, 1); var json = (ObjectNode) f.mapper.readTree(s.reportJson());
        var gaze = (ObjectNode) json.path("analytics").path("streams").get(0).path("gazePlot");
        gaze.withArray("points").removeAll();
        String emptyText = text(renderer.render(copy(s, json)));
        assertTrue(emptyText.contains("No points in this saved selection."));
        assertTrue(emptyText.contains("Partial saved selection"));
        assertFalse(emptyText.contains("Sin evidencia"));
        gaze.withArray("points").add(gazePoint(0, .1, .2, true));
        try (var doc = Loader.loadPDF(renderer.render(copy(s, json)))) {
            assertEquals(1, gazeMarks(doc).size()); assertTrue(gazeLines(doc).isEmpty());
        }
        gaze.put("startsAtBeginning", true).put("hasNext", true);
        assertTrue(text(renderer.render(copy(s, json))).contains("Partial saved selection"));
        // A complete empty stream is still not analytical absence.
        long[] zero = new long[100];
        assertTrue(text(renderer.render(observed(zero))).contains("No points in this saved selection."));
    }

    @Test void invalidSavedGazeSelectionsFailClosedWithoutWritesOrPeerReads() throws Exception {
        var s = gazeCopy(true, true, 1);
        for (int kind = 0; kind < 10; kind++) {
            var json = (ObjectNode) f.mapper.readTree(s.reportJson());
            var gaze = (ObjectNode) json.path("analytics").path("streams").get(0).path("gazePlot");
            var first = (ObjectNode) gaze.path("points").get(0);
            switch (kind) {
                case 0 -> first.put("x", -0.1);
                case 1 -> first.put("y", 1.1);
                case 2 -> first.put("x", "0.1");
                case 3 -> first.remove("segmentBreakBefore");
                case 4 -> gaze.put("hasNext", "true");
                case 5 -> gaze.put("pageSize", 501);
                case 6 -> gaze.put("pageSize", 1);
                case 7 -> ((ObjectNode) gaze.path("points").get(1)).put("gazeSampleIndex", 0);
                case 8 -> first.put("effectiveTime", "invalid");
                case 9 -> gaze.put("startsAtBeginning", true).put("hasNext", false);
            }
            var bad = copy(s, json); int peers = f.peers, writes = f.writes;
            var error = assertThrows(IllegalStateException.class, () -> renderer.render(bad));
            assertEquals("Session report PDF unavailable", error.getMessage()); assertNull(error.getCause());
            assertEquals(peers, f.peers); assertEquals(writes, f.writes); assertEquals(json.toString(), bad.reportJson());
        }
    }

    private ObjectNode gazePoint(int index, double x, double y, boolean cut) {
        return f.mapper.createObjectNode().put("sequence", 1).put("gazeSampleIndex", index)
                .put("effectiveTime", com.neurovision.rehab.clinical.application.internal.commandservices.CreateSessionReportSnapshotServiceTest.TIME.toString())
                .put("x", x).put("y", y).put("confidence", .5).put("segmentBreakBefore", cut);
    }
    private Snapshot gazeCopy(boolean beginning, boolean next, int streamCount) throws Exception {
        long[] cells = new long[100]; cells[0] = 10;
        var values = new long[streamCount][]; Arrays.fill(values, cells);
        var s = observed(values); var json = (ObjectNode) f.mapper.readTree(s.reportJson());
        for (var stream : json.path("analytics").path("streams")) {
            var gaze = (ObjectNode) stream.path("gazePlot");
            gaze.put("startsAtBeginning", beginning).put("hasNext", next);
            var points = gaze.withArray("points");
            points.add(gazePoint(0, .1, .2, true)); points.add(gazePoint(1, .4, .5, false));
            points.add(gazePoint(2, .8, .9, true)); points.add(gazePoint(3, .9, .1, false));
        }
        return copy(s, json);
    }
    private record Line(int page, float x1, float y1, float x2, float y2) {}
    private List<Cell> gazeMarks(org.apache.pdfbox.pdmodel.PDDocument doc) throws Exception {
        var result = new ArrayList<Cell>();
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            var tokens = new PDFStreamParser(doc.getPage(p)).parse();
            for (int i = 4; i < tokens.size(); i++) if (tokens.get(i) instanceof Operator op && op.getName().equals("re")
                    && ((COSNumber) tokens.get(i - 2)).floatValue() == 4 && ((COSNumber) tokens.get(i - 1)).floatValue() == 4)
                result.add(new Cell(p, ((COSNumber) tokens.get(i - 4)).floatValue(), ((COSNumber) tokens.get(i - 3)).floatValue()));
        }
        return result;
    }
    private List<Line> gazeLines(org.apache.pdfbox.pdmodel.PDDocument doc) throws Exception {
        var result = new ArrayList<Line>();
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            var tokens = new PDFStreamParser(doc.getPage(p)).parse(); boolean blue = false;
            for (int i = 3; i < tokens.size(); i++) if (tokens.get(i) instanceof Operator op) {
                if (op.getName().equals("G")) blue = false;
                if (op.getName().equals("RG")) blue = Math.abs(((COSNumber) tokens.get(i - 3)).floatValue() - .15f) < .001;
                if (blue && op.getName().equals("l") && i >= 5 && tokens.get(i - 3) instanceof Operator prior && prior.getName().equals("m"))
                    result.add(new Line(p, ((COSNumber) tokens.get(i - 5)).floatValue(), ((COSNumber) tokens.get(i - 4)).floatValue(),
                            ((COSNumber) tokens.get(i - 2)).floatValue(), ((COSNumber) tokens.get(i - 1)).floatValue()));
            }
        }
        return result;
    }

    private record Cell(int page, float x, float y) {}
    private List<Cell> gridCells(org.apache.pdfbox.pdmodel.PDDocument doc) throws Exception {
        var cells = new ArrayList<Cell>();
        for (int page = 0; page < doc.getNumberOfPages(); page++) {
            var tokens = new PDFStreamParser(doc.getPage(page)).parse();
            for (int i = 4; i < tokens.size(); i++) if (tokens.get(i) instanceof Operator op && op.getName().equals("re")
                    && tokens.get(i - 2) instanceof COSNumber width && tokens.get(i - 1) instanceof COSNumber height
                    && Math.abs(width.floatValue() - 28) < .01 && Math.abs(height.floatValue() - 28) < .01)
                cells.add(new Cell(page, ((COSNumber) tokens.get(i - 4)).floatValue(), ((COSNumber) tokens.get(i - 3)).floatValue()));
        }
        return cells;
    }

    private Snapshot observed(long[]... values) {
        var saved = original(); var base = f.composer().compose("synthetic-doctor", "synthetic-token", saved.clinicalPatientId(), saved.therapySessionId());
        var streams = new ArrayList<Stream>();
        for (int i = 0; i < values.length; i++) {
            var cells = Arrays.stream(values[i]).boxed().toList(); long count = cells.stream().reduce(0L, Math::addExact);
            var source = new Source(saved.therapySessionId(), new UUID(0, 80 + i), "baseline-v1", 1 + i, 1, 1);
            var complete = new Completeness(1, 3, 3, 2, true, List.of(new Range(2, 2)), 1, true,
                    count == 0 ? null : new TimeRange(saved.createdAt(), saved.createdAt()), new Counts(count, 0, count));
            var grid = new Grid(10, 10, cells); var empty = new Numeric(0, null, null, null);
            var confidence = count == 0 ? empty : new Numeric(count, .5, .5, .5);
            var histogram = new ArrayList<>(Collections.nCopies(10, 0L)); histogram.set(5, count);
            int occupied = (int) cells.stream().filter(n -> n > 0).count(); var ordered = new Ordered(empty, null, null, null);
            var summary = new Summary(source, complete, confidence, histogram, grid,
                    new Coverage(count == 0 ? null : new BoundingBox(0, 0, 1, 1, 1, 1, 1), occupied, occupied / 100.0),
                    new com.neurovision.rehab.clinical.application.ports.out.integration.SessionReportAnalyticsPort.Path(0, empty, 0),
                    new Gameplay(empty, ordered, ordered, ordered));
            streams.add(new Stream(summary, new Heatmap(source, complete, grid, "NORMALIZED_ZERO_TO_ONE", "RAW_GAZE_SAMPLE_COUNT", false),
                    new GazePage(source, complete, 100, 64, true, count > 0, List.of())));
        }
        var report = new SessionReportV1(base.clinicalPatientId(), base.doctor(), base.patient(), base.execution(), base.configuration(),
                base.sessionLimits(), base.technicalProgress(), new ObservedEvidence(saved.therapySessionId(), 0, 20, false, streams), base.issuedAt());
        return copy(saved, (ObjectNode) SessionReportRestAssembler.toResponse(report, f.mapper).representation());
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
