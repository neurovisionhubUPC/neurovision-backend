package com.neurovision.rehab.analytics.domain.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.interfaces.events.transform.TelemetryBatchAcceptedV1Decoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaselineTelemetryMetricsGoldenTest {
    private static final String KEY =
            "00000000-0000-0000-0000-000000000003:00000000-0000-0000-0000-000000000004";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void frozenKafkaV1FixtureProducesTheVersionedBaselineGoldenSummary() throws Exception {
        var wire = resource("/fixtures/telemetry/v1/accepted-event-wire.json");
        var decoded = TelemetryBatchAcceptedV1Decoder.decode(KEY, wire, mapper, 65_536, 100,
                Duration.ofSeconds(60), Instant.parse("2026-09-17T10:00:05Z"));
        var summary = new BaselineTelemetryMetricsCalculator().calculate(List.of(
                new AdmittedTelemetryBatch(decoded, TelemetryAdmissionQuality.INITIAL)));

        JsonNode expected = mapper.readTree(resource("/fixtures/analytics/v1/baseline-summary.json"));
        JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(json(summary)));
        assertEquals(expected, actual, () -> actual.toPrettyString());
    }

    private ObjectNode json(BaselineStreamSummary summary) {
        var root = mapper.createObjectNode();
        root.put("calculationVersion", summary.calculationVersion());
        root.put("sourceSchemaVersion", summary.sourceSchemaVersion());
        root.put("therapySessionId", summary.therapySessionId().toString());
        root.put("streamId", summary.streamId().toString());
        root.put("minimumSequence", summary.minimumSequence());
        root.put("maximumSequence", summary.maximumSequence());
        root.put("highSequence", summary.highSequence());
        var missing = root.putArray("missingSequenceRanges");
        summary.missingSequenceRanges().forEach(range -> missing.addObject()
                .put("fromInclusive", range.fromInclusive()).put("toInclusive", range.toInclusive()));
        root.put("lateArrivalCount", summary.lateArrivalCount());
        root.put("unknownSessionBoundary", summary.unknownSessionBoundary());
        summary.observedTimeRange().ifPresent(range -> root.putObject("observedTimeRange")
                .put("minimum", range.minimum().toString())
                .put("maximum", range.maximum().toString())
                .put("spanMillis", range.span().toMillis()));
        root.putObject("sampleCounts").put("gaze", summary.sampleCounts().gaze())
                .put("gameplay", summary.sampleCounts().gameplay())
                .put("total", summary.sampleCounts().total());
        root.set("confidence", numeric(summary.confidence()));
        array(root, "confidenceHistogram", summary.confidenceHistogram());
        var heatmap = root.putObject("heatmap");
        heatmap.put("rows", summary.heatmap().rows()).put("columns", summary.heatmap().columns());
        array(heatmap, "cellCounts", summary.heatmap().cellCounts());
        var coverage = root.putObject("coverage");
        summary.coverage().boundingBox().ifPresent(box -> coverage.putObject("boundingBox")
                .put("minimumX", box.minimumX()).put("minimumY", box.minimumY())
                .put("maximumX", box.maximumX()).put("maximumY", box.maximumY())
                .put("width", box.width()).put("height", box.height()).put("area", box.area()));
        coverage.put("occupiedCellCount", summary.coverage().occupiedCellCount())
                .put("occupiedCellRatio", summary.coverage().occupiedCellRatio());
        root.putObject("gazePath").put("totalDistance", summary.gazePath().totalDistance())
                .set("velocity", numeric(summary.gazePath().velocity()));
        ((ObjectNode) root.get("gazePath")).put(
                "excludedNonPositiveDeltaCount", summary.gazePath().excludedNonPositiveDeltaCount());
        var gameplay = root.putObject("gameplay");
        gameplay.set("playerTargetDistance", numeric(summary.gameplay().playerTargetDistance()));
        gameplay.set("score", ordered(summary.gameplay().score()));
        gameplay.set("speed", ordered(summary.gameplay().speed()));
        gameplay.set("progress", ordered(summary.gameplay().progress()));
        return root;
    }

    private ObjectNode numeric(BaselineStreamSummary.NumericSummary summary) {
        var node = mapper.createObjectNode().put("count", summary.count());
        optional(node, "minimum", summary.minimum());
        optional(node, "maximum", summary.maximum());
        optional(node, "mean", summary.mean());
        return node;
    }

    private ObjectNode ordered(BaselineStreamSummary.OrderedNumericSummary summary) {
        var node = mapper.createObjectNode();
        node.set("distribution", numeric(summary.distribution()));
        optional(node, "first", summary.first());
        optional(node, "last", summary.last());
        optional(node, "signedDelta", summary.signedDelta());
        return node;
    }

    private void optional(ObjectNode node, String name, java.util.OptionalDouble value) {
        if (value.isPresent()) node.put(name, value.getAsDouble());
        else node.putNull(name);
    }

    private void array(ObjectNode node, String name, List<Long> values) {
        var array = node.putArray(name);
        values.forEach(array::add);
    }

    private String resource(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test fixture: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
