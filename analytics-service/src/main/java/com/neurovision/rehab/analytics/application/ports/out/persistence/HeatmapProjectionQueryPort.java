package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.HeatmapProjection;
import io.smallrye.mutiny.Uni;
import java.util.Objects;

public interface HeatmapProjectionQueryPort {
    Uni<Result> load(AnalyticsProjectionIdentity identity);

    sealed interface Result permits Found, NotFound {}

    record Found(HeatmapProjection projection) implements Result {
        public Found {
            Objects.requireNonNull(projection, "projection");
        }
    }

    enum NotFound implements Result {
        INSTANCE
    }

    final class MalformedProjectionException extends RuntimeException {
        public MalformedProjectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
