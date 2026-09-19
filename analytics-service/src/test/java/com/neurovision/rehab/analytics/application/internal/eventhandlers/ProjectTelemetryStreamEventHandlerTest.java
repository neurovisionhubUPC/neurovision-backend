package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.neurovision.rehab.analytics.application.ports.out.persistence.AdmittedTelemetryStreamSourcePort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StreamSummaryProjectionPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryStream;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectTelemetryStreamEventHandlerTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final TelemetryStreamIdentity IDENTITY = new TelemetryStreamIdentity(SESSION, STREAM);

    @Test
    void calculatesFromTheCompleteReconstructedStreamAndStoresItsBatchCountRevision() {
        var reconstructed = new AdmittedTelemetryStream(IDENTITY, 2,
                List.of(admitted(0, TelemetryAdmissionQuality.INITIAL),
                        admitted(2, TelemetryAdmissionQuality.GAP_DETECTED)));
        var stored = new AtomicReference<StreamSummaryProjection>();
        AdmittedTelemetryStreamSourcePort source = identity -> {
            assertSame(IDENTITY, identity);
            return Uni.createFrom().item(reconstructed);
        };
        StreamSummaryProjectionPort projections = projection -> {
            stored.set(projection);
            return Uni.createFrom().item(StreamSummaryProjectionPort.WriteResult.INSERTED);
        };

        new ProjectTelemetryStreamEventHandler(source, projections).project(IDENTITY).await().indefinitely();

        assertEquals(2, stored.get().admittedBatchCount());
        assertEquals(IDENTITY.therapySessionId(), stored.get().identity().therapySessionId());
        assertEquals(IDENTITY.streamId(), stored.get().identity().streamId());
        assertEquals("baseline-v1", stored.get().identity().calculationVersion());
        assertEquals(2, stored.get().summary().sampleCounts().gaze());
        assertEquals(1, stored.get().summary().missingSequenceRanges().size());
    }

    @Test
    void rejectsACompleteStreamThatDoesNotMatchTheRequestedIdentity() {
        var other = new TelemetryStreamIdentity(UUID.randomUUID(), STREAM);
        var reconstructed = new AdmittedTelemetryStream(other, 1,
                List.of(admitted(other, 0, TelemetryAdmissionQuality.INITIAL)));
        AdmittedTelemetryStreamSourcePort source = ignored -> Uni.createFrom().item(reconstructed);
        StreamSummaryProjectionPort projections = ignored ->
                Uni.createFrom().item(StreamSummaryProjectionPort.WriteResult.INSERTED);

        assertThrows(IllegalArgumentException.class,
                () -> new ProjectTelemetryStreamEventHandler(source, projections)
                        .project(IDENTITY).await().indefinitely());
    }

    @Test
    void lateArrivalAdvancesBatchCountEvenWhenHighSequenceDoesNotChange() {
        var current = new AtomicReference<>(new AdmittedTelemetryStream(IDENTITY, 2,
                List.of(admitted(0, TelemetryAdmissionQuality.INITIAL),
                        admitted(2, TelemetryAdmissionQuality.GAP_DETECTED))));
        var stored = new ArrayList<StreamSummaryProjection>();
        AdmittedTelemetryStreamSourcePort source = ignored -> Uni.createFrom().item(current.get());
        StreamSummaryProjectionPort projections = projection -> {
            stored.add(projection);
            return Uni.createFrom().item(stored.size() == 1
                    ? StreamSummaryProjectionPort.WriteResult.INSERTED
                    : StreamSummaryProjectionPort.WriteResult.ADVANCED);
        };
        var handler = new ProjectTelemetryStreamEventHandler(source, projections);

        handler.project(IDENTITY).await().indefinitely();
        current.set(new AdmittedTelemetryStream(IDENTITY, 3,
                List.of(admitted(0, TelemetryAdmissionQuality.INITIAL),
                        admitted(1, TelemetryAdmissionQuality.LATE_ARRIVAL),
                        admitted(2, TelemetryAdmissionQuality.GAP_DETECTED))));
        handler.project(IDENTITY).await().indefinitely();

        assertEquals(List.of(2L, 2L), stored.stream().map(item -> item.summary().highSequence()).toList());
        assertEquals(List.of(2L, 3L), stored.stream().map(StreamSummaryProjection::admittedBatchCount).toList());
        assertEquals(1, stored.getFirst().summary().missingSequenceRanges().size());
        assertEquals(0, stored.getLast().summary().missingSequenceRanges().size());
        assertEquals(1, stored.getLast().summary().lateArrivalCount());
    }

    private AdmittedTelemetryBatch admitted(long sequence, TelemetryAdmissionQuality quality) {
        return admitted(IDENTITY, sequence, quality);
    }

    private AdmittedTelemetryBatch admitted(
            TelemetryStreamIdentity identity, long sequence, TelemetryAdmissionQuality quality) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(
                1, UUID.randomUUID(), UUID.randomUUID(), identity.therapySessionId(), identity.streamId(),
                sequence, time, time.plusMillis(1), time.plusMillis(2), UUID.randomUUID(), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(),
                "sha256-" + sequence);
        return new AdmittedTelemetryBatch(batch, quality);
    }
}
