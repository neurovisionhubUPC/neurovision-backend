package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AdmittedTelemetryStream;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import io.smallrye.mutiny.Uni;

public interface AdmittedTelemetryStreamSourcePort {
    /**
     * Loads the complete admitted durable stream. Missing or malformed persisted state must fail the returned
     * operation rather than return a partial stream.
     */
    Uni<AdmittedTelemetryStream> loadCompleteStream(TelemetryStreamIdentity identity);
}
