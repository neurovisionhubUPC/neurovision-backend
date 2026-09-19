package com.neurovision.rehab.analytics.application.internal.eventhandlers;

import com.neurovision.rehab.analytics.application.ports.in.ProjectTelemetryStreamUseCase;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AdmittedTelemetryStreamSourcePort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StreamSummaryProjectionPort;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StreamSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.function.Supplier;

@ApplicationScoped
public class ProjectTelemetryStreamEventHandler implements ProjectTelemetryStreamUseCase {
    private final Supplier<AdmittedTelemetryStreamSourcePort> streamSource;
    private final Supplier<StreamSummaryProjectionPort> projectionStore;
    private final BaselineTelemetryMetricsCalculator calculator = new BaselineTelemetryMetricsCalculator();

    @Inject
    public ProjectTelemetryStreamEventHandler(
            Instance<AdmittedTelemetryStreamSourcePort> streamSources,
            Instance<StreamSummaryProjectionPort> projectionStores) {
        this(streamSources::get, projectionStores::get);
    }

    ProjectTelemetryStreamEventHandler(
            AdmittedTelemetryStreamSourcePort streamSource,
            StreamSummaryProjectionPort projectionStore) {
        this(() -> Objects.requireNonNull(streamSource, "streamSource"),
                () -> Objects.requireNonNull(projectionStore, "projectionStore"));
    }

    private ProjectTelemetryStreamEventHandler(
            Supplier<AdmittedTelemetryStreamSourcePort> streamSource,
            Supplier<StreamSummaryProjectionPort> projectionStore) {
        this.streamSource = streamSource;
        this.projectionStore = projectionStore;
    }

    @Override
    public Uni<Void> project(TelemetryStreamIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return Uni.createFrom().deferred(() -> streamSource.get().loadCompleteStream(identity))
                .map(stream -> {
                    if (!identity.equals(stream.identity())) {
                        throw new IllegalArgumentException("Durable source returned another stream");
                    }
                    var summary = calculator.calculate(stream.batches());
                    return new StreamSummaryProjection(
                            AnalyticsProjectionIdentity.from(summary), stream.admittedBatchCount(), summary);
                })
                .chain(projection -> projectionStore.get().storeIfNewer(projection))
                .replaceWithVoid();
    }
}
