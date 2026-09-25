package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import io.smallrye.mutiny.Uni;

public interface AnalyticsSummaryQueryPort {
    Uni<Result> load(AnalyticsProjectionIdentity identity);

    sealed interface Result permits Found, NotFound {}

    record Found(AnalyticsSummaryProjection projection) implements Result {}

    enum NotFound implements Result { INSTANCE }
}
