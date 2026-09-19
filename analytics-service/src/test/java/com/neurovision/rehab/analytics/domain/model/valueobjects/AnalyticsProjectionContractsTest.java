package com.neurovision.rehab.analytics.domain.model.valueobjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsProjectionContractsTest {
    private static final UUID THERAPY_SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void reconstructsAnImmutableCompleteStreamWithARevisionEqualToItsBatchCount() {
        var mutable = new ArrayList<>(List.of(admitted(0), admitted(1)));

        var stream = new AdmittedTelemetryStream(
                new TelemetryStreamIdentity(THERAPY_SESSION_ID, STREAM_ID), 2, mutable);
        mutable.clear();

        assertEquals(2, stream.admittedBatchCount());
        assertEquals(List.of(0L, 1L), stream.batches().stream().map(item -> item.batch().sequence()).toList());
        assertThrows(UnsupportedOperationException.class, () -> stream.batches().add(admitted(2)));
    }

    @Test
    void failsClosedWhenReconstructionIsEmptyIncompleteOrIdentityInconsistent() {
        var identity = new TelemetryStreamIdentity(THERAPY_SESSION_ID, STREAM_ID);

        assertThrows(IllegalArgumentException.class, () -> new AdmittedTelemetryStream(identity, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmittedTelemetryStream(identity, 2, List.of(admitted(0))));
        assertThrows(NullPointerException.class,
                () -> new AdmittedTelemetryStream(identity, 1, List.of(admittedWithoutCorrelation())));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmittedTelemetryStream(identity, 1, List.of(admittedWithoutSamples())));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmittedTelemetryStream(identity, 1, List.of(admittedForAnotherStream())));
    }

    @Test
    void rejectsDuplicateBatchOrSequenceDuringReconstruction() {
        var identity = new TelemetryStreamIdentity(THERAPY_SESSION_ID, STREAM_ID);
        var first = admitted(0);
        var sameBatch = new AdmittedTelemetryBatch(batch(first.batch().batchId(), 1, STREAM_ID, UUID.randomUUID()),
                TelemetryAdmissionQuality.IN_ORDER);
        var sameSequence = new AdmittedTelemetryBatch(batch(UUID.randomUUID(), 0, STREAM_ID, UUID.randomUUID()),
                TelemetryAdmissionQuality.IN_ORDER);

        assertThrows(IllegalArgumentException.class,
                () -> new AdmittedTelemetryStream(identity, 2, List.of(first, sameBatch)));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmittedTelemetryStream(identity, 2, List.of(first, sameSequence)));
    }

    @Test
    void bindsSummaryStorageToIdentityAndMonotonicBatchCountRevision() {
        var stream = new AdmittedTelemetryStream(
                new TelemetryStreamIdentity(THERAPY_SESSION_ID, STREAM_ID), 2,
                List.of(admitted(0), admitted(1)));
        var summary = new BaselineTelemetryMetricsCalculator().calculate(stream.batches());
        var identity = AnalyticsProjectionIdentity.from(summary);

        var projection = new StreamSummaryProjection(identity, stream.admittedBatchCount(), summary);

        assertEquals(THERAPY_SESSION_ID, projection.identity().therapySessionId());
        assertEquals(STREAM_ID, projection.identity().streamId());
        assertEquals("baseline-v1", projection.identity().calculationVersion());
        assertEquals(2, projection.admittedBatchCount());
        assertThrows(IllegalArgumentException.class,
                () -> new StreamSummaryProjection(identity, 0, summary));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamSummaryProjection(
                        new AnalyticsProjectionIdentity(THERAPY_SESSION_ID, STREAM_ID, "baseline-v2"),
                        2, summary));
    }

    private AdmittedTelemetryBatch admitted(long sequence) {
        return new AdmittedTelemetryBatch(
                batch(UUID.randomUUID(), sequence, STREAM_ID, UUID.randomUUID()),
                sequence == 0 ? TelemetryAdmissionQuality.INITIAL : TelemetryAdmissionQuality.IN_ORDER);
    }

    private AdmittedTelemetryBatch admittedWithoutCorrelation() {
        return new AdmittedTelemetryBatch(batch(UUID.randomUUID(), 0, STREAM_ID, null),
                TelemetryAdmissionQuality.INITIAL);
    }

    private AdmittedTelemetryBatch admittedForAnotherStream() {
        return new AdmittedTelemetryBatch(
                batch(UUID.randomUUID(), 0, UUID.randomUUID(), UUID.randomUUID()),
                TelemetryAdmissionQuality.INITIAL);
    }

    private AdmittedTelemetryBatch admittedWithoutSamples() {
        var capturedAt = Instant.parse("2026-09-18T10:00:00Z");
        var batchId = UUID.randomUUID();
        return new AdmittedTelemetryBatch(new AcceptedTelemetryBatch(
                1, UUID.randomUUID(), batchId, THERAPY_SESSION_ID, STREAM_ID, 0,
                capturedAt, capturedAt.plusMillis(10), capturedAt.plusMillis(20), UUID.randomUUID(), null,
                List.of(), List.of(), "sha256-" + batchId), TelemetryAdmissionQuality.INITIAL);
    }

    private AcceptedTelemetryBatch batch(UUID batchId, long sequence, UUID streamId, UUID correlationId) {
        var capturedAt = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        return new AcceptedTelemetryBatch(
                1,
                UUID.randomUUID(),
                batchId,
                THERAPY_SESSION_ID,
                streamId,
                sequence,
                capturedAt,
                capturedAt.plusMillis(10),
                capturedAt.plusMillis(20),
                correlationId,
                null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)),
                List.of(),
                "sha256-" + batchId);
    }
}
