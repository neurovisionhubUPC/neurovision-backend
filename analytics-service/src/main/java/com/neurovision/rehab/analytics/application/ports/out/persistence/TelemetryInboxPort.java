package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryAdmissionQuality;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryInboxPort {
    Uni<Void> prepare();
    Uni<Optional<StoredIdentity>> findByBatchId(UUID batchId);
    Uni<Optional<StoredIdentity>> findBySequence(UUID therapySessionId, UUID streamId, long sequence);
    Uni<Optional<Long>> maximumSequence(UUID therapySessionId, UUID streamId);
    Uni<Void> insert(AcceptedTelemetryBatch batch, TelemetryAdmissionQuality quality,
            Provenance provenance, Instant admittedAt);

    record StoredIdentity(UUID batchId, String payloadSha256) {}
    record Provenance(String topic, int partition, long offset, int payloadBytes) {}
    final class ConcurrentInsertException extends RuntimeException {
        public ConcurrentInsertException() { super("Concurrent inbox insert"); }
    }
}
