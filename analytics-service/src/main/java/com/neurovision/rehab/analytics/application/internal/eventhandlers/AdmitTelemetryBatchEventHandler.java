package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort.ConcurrentInsertException;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class AdmitTelemetryBatchEventHandler {
    private final TelemetryInboxPort inbox;

    @Inject
    public AdmitTelemetryBatchEventHandler(TelemetryInboxPort inbox) {
        this.inbox = inbox;
    }

    public Uni<Result> admit(AcceptedTelemetryBatch batch, TelemetryInboxPort.Provenance provenance) {
        return inbox.prepare()
                .chain(() -> classifyExisting(batch))
                .chain(existing -> existing.isPresent()
                        ? Uni.createFrom().item(existing.get())
                        : inbox.maximumSequence(batch.therapySessionId(), batch.streamId())
                                .map(max -> quality(batch.sequence(), max))
                                .chain(quality -> inbox.insert(batch, quality, provenance, Instant.now())
                                        .replaceWith(new Result(Status.ADMITTED, quality))))
                .onFailure(ConcurrentInsertException.class).recoverWithUni(ignored -> classifyAfterRace(batch));
    }

    private Uni<Optional<Result>> classifyExisting(AcceptedTelemetryBatch batch) {
        return inbox.findByBatchId(batch.batchId()).chain(byBatch -> {
            if (byBatch.isPresent()) {
                return Uni.createFrom().item(Optional.of(new Result(
                        byBatch.get().payloadSha256().equals(batch.payloadSha256())
                                ? Status.DUPLICATE : Status.BATCH_CONFLICT, null)));
            }
            return inbox.findBySequence(batch.therapySessionId(), batch.streamId(), batch.sequence())
                    .map(bySequence -> bySequence.isPresent()
                            ? Optional.of(new Result(Status.SEQUENCE_CONFLICT, null))
                            : Optional.empty());
        });
    }

    private Uni<Result> classifyAfterRace(AcceptedTelemetryBatch batch) {
        return classifyExisting(batch).chain(result -> result
                .map(Uni.createFrom()::item)
                .orElseGet(() -> Uni.createFrom().failure(new IllegalStateException("Inbox race unresolved"))));
    }

    private TelemetryAdmissionQuality quality(long sequence, Optional<Long> maximum) {
        if (maximum.isEmpty()) return TelemetryAdmissionQuality.INITIAL;
        long max = maximum.get();
        if (max != Long.MAX_VALUE && sequence == max + 1) return TelemetryAdmissionQuality.IN_ORDER;
        return sequence > max ? TelemetryAdmissionQuality.GAP_DETECTED : TelemetryAdmissionQuality.LATE_ARRIVAL;
    }

    public record Result(Status status, TelemetryAdmissionQuality quality) {}
    public enum Status { ADMITTED, DUPLICATE, BATCH_CONFLICT, SEQUENCE_CONFLICT }
}
