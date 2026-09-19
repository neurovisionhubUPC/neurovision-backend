package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler.Result;
import com.neurovision.rehab.analytics.application.internal.eventhandlers.AdmitTelemetryBatchEventHandler.Status;
import com.neurovision.rehab.analytics.application.ports.in.ProjectTelemetryStreamUseCase;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort.Provenance;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AcceptedTelemetryBatch;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

@ApplicationScoped
public class AdmitAndProjectTelemetryBatchEventHandler {
    private final AdmitTelemetryBatchEventHandler admission;
    private final ProjectTelemetryStreamUseCase projection;

    @Inject
    public AdmitAndProjectTelemetryBatchEventHandler(
            AdmitTelemetryBatchEventHandler admission,
            ProjectTelemetryStreamUseCase projection) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    public Uni<Result> handle(AcceptedTelemetryBatch batch, Provenance provenance) {
        return admission.admit(batch, provenance)
                .chain(result -> shouldProject(result.status())
                        ? projection.project(new TelemetryStreamIdentity(
                                batch.therapySessionId(), batch.streamId())).replaceWith(result)
                        : Uni.createFrom().item(result));
    }

    private boolean shouldProject(Status status) {
        return status == Status.ADMITTED || status == Status.DUPLICATE;
    }
}
