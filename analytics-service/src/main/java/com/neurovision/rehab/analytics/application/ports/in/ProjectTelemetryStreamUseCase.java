package com.neurovision.rehab.analytics.application.ports.in;

import com.neurovision.rehab.analytics.domain.model.valueobjects.TelemetryStreamIdentity;
import io.smallrye.mutiny.Uni;

public interface ProjectTelemetryStreamUseCase {
    Uni<Void> project(TelemetryStreamIdentity identity);
}
