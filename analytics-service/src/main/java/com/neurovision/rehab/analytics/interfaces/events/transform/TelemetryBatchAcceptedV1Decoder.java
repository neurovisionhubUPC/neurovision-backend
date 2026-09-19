package com.neurovision.rehab.analytics.interfaces.events.transform;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.interfaces.events.contracts.TelemetryBatchAcceptedV1;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class TelemetryBatchAcceptedV1Decoder {
    private static final byte[] FINGERPRINT_PREFIX = "NVRA-V1\0".getBytes(StandardCharsets.US_ASCII);

    private TelemetryBatchAcceptedV1Decoder() {}

    public static AcceptedTelemetryBatch decode(String key, String json, ObjectMapper mapper,
            int maxEventBytes, int maxSamples, Duration futureSkew, Instant now) {
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > maxEventBytes) {
            throw new IllegalArgumentException("Invalid analytics event size");
        }
        final TelemetryBatchAcceptedV1 event;
        try {
            var tree = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(json);
            requireNumbers(tree, "schemaVersion", "sequence");
            requireSampleNumbers(tree.get("gazeSamples"), "offsetMs", "x", "y", "confidence");
            requireSampleNumbers(tree.get("gameplaySamples"), "offsetMs", "playerX", "playerY",
                    "targetX", "targetY", "score", "speed", "progress");
            event = mapper.readerFor(TelemetryBatchAcceptedV1.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(tree.traverse(mapper));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid analytics event contract");
        }
        validate(key, event, maxSamples, futureSkew, now);
        var gaze = event.gazeSamples().stream().map(s -> new AcceptedTelemetryBatch.GazeSample(
                s.offsetMs(), s.x(), s.y(), s.confidence())).toList();
        var gameplay = event.gameplaySamples().stream().map(s -> new AcceptedTelemetryBatch.GameplaySample(
                s.offsetMs(), s.playerX(), s.playerY(), s.targetX(), s.targetY(),
                s.score(), s.speed(), s.progress())).toList();
        var withoutHash = new AcceptedTelemetryBatch(event.schemaVersion(), event.eventId(), event.batchId(),
                event.therapySessionId(), event.streamId(), event.sequence(), event.capturedAt(), event.sentAt(),
                event.serverReceivedAt(), event.correlationId(), event.traceId(), gaze, gameplay, "");
        return new AcceptedTelemetryBatch(withoutHash.schemaVersion(), withoutHash.eventId(), withoutHash.batchId(),
                withoutHash.therapySessionId(), withoutHash.streamId(), withoutHash.sequence(),
                withoutHash.capturedAt(), withoutHash.sentAt(), withoutHash.serverReceivedAt(),
                withoutHash.correlationId(), withoutHash.traceId(), gaze, gameplay, fingerprint(withoutHash));
    }

    private static void requireSampleNumbers(JsonNode samples, String... fields) {
        if (samples == null || !samples.isArray()) return;
        samples.forEach(sample -> requireNumbers(sample, fields));
    }

    private static void requireNumbers(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing numeric fields");
        for (var field : fields) {
            if (!node.hasNonNull(field) || !node.get(field).isNumber()) {
                throw new IllegalArgumentException("Missing numeric fields");
            }
        }
    }

    private static void validate(String key, TelemetryBatchAcceptedV1 event, int maxSamples,
            Duration futureSkew, Instant now) {
        if (event == null || event.schemaVersion() != 1 || event.eventId() == null || event.batchId() == null
                || event.therapySessionId() == null || event.streamId() == null || event.correlationId() == null
                || event.capturedAt() == null || event.sentAt() == null || event.serverReceivedAt() == null
                || event.sequence() < 0 || event.gazeSamples() == null || event.gameplaySamples() == null
                || event.gazeSamples().stream().anyMatch(Objects::isNull)
                || event.gameplaySamples().stream().anyMatch(Objects::isNull)
                || !key.equals(event.therapySessionId() + ":" + event.streamId())
                || event.capturedAt().isAfter(event.sentAt())
                || event.sentAt().isAfter(event.serverReceivedAt().plus(futureSkew))
                || event.serverReceivedAt().isAfter(now.plus(futureSkew))) {
            throw new IllegalArgumentException("Invalid analytics event metadata");
        }
        long count = (long) event.gazeSamples().size() + event.gameplaySamples().size();
        if (count == 0 || count > maxSamples) throw new IllegalArgumentException("Invalid analytics sample count");
        long offset = -1;
        for (var sample : event.gazeSamples()) {
            if (sample.offsetMs() < offset || sample.offsetMs() < 0 || !normalized(sample.x())
                    || !normalized(sample.y()) || !normalized(sample.confidence())) {
                throw new IllegalArgumentException("Invalid analytics gaze sample");
            }
            sampleTime(event.capturedAt(), sample.offsetMs());
            offset = sample.offsetMs();
        }
        offset = -1;
        for (var sample : event.gameplaySamples()) {
            if (sample.offsetMs() < offset || sample.offsetMs() < 0 || !normalized(sample.playerX())
                    || !normalized(sample.playerY()) || !normalized(sample.targetX())
                    || !normalized(sample.targetY()) || sample.score() < 0
                    || !Double.isFinite(sample.speed()) || sample.speed() < 0 || !normalized(sample.progress())) {
                throw new IllegalArgumentException("Invalid analytics gameplay sample");
            }
            sampleTime(event.capturedAt(), sample.offsetMs());
            offset = sample.offsetMs();
        }
    }

    private static void sampleTime(Instant capturedAt, long offsetMs) {
        try { capturedAt.plusMillis(offsetMs); }
        catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid analytics sample time");
        }
    }

    private static boolean normalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    /** SHA-256 over a versioned big-endian binary encoding of every logical V1 field. */
    static String fingerprint(AcceptedTelemetryBatch batch) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.write(FINGERPRINT_PREFIX);
                out.writeInt(batch.schemaVersion());
                uuid(out, batch.batchId()); uuid(out, batch.therapySessionId()); uuid(out, batch.streamId());
                out.writeLong(batch.sequence()); instant(out, batch.capturedAt()); instant(out, batch.sentAt());
                out.writeInt(batch.gazeSamples().size());
                for (var s : batch.gazeSamples()) {
                    out.writeLong(s.offsetMs()); out.writeLong(Double.doubleToRawLongBits(s.x()));
                    out.writeLong(Double.doubleToRawLongBits(s.y()));
                    out.writeLong(Double.doubleToRawLongBits(s.confidence()));
                }
                out.writeInt(batch.gameplaySamples().size());
                for (var s : batch.gameplaySamples()) {
                    out.writeLong(s.offsetMs()); out.writeLong(Double.doubleToRawLongBits(s.playerX()));
                    out.writeLong(Double.doubleToRawLongBits(s.playerY()));
                    out.writeLong(Double.doubleToRawLongBits(s.targetX()));
                    out.writeLong(Double.doubleToRawLongBits(s.targetY())); out.writeLong(s.score());
                    out.writeLong(Double.doubleToRawLongBits(s.speed()));
                    out.writeLong(Double.doubleToRawLongBits(s.progress()));
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not fingerprint analytics batch", impossible);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws java.io.IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }

    private static void instant(DataOutputStream out, Instant value) throws java.io.IOException {
        out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano());
    }
}
