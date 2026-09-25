package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayPage;
import io.smallrye.mutiny.Uni;

public interface ReplayPageQueryPort {
    Uni<Result> loadPage(AnalyticsProjectionIdentity identity, ReplayCursor gazeCursor,
            ReplayCursor gameplayCursor, int pageSize);

    sealed interface Result permits Found, NotFound {}
    record Found(ReplayPage page) implements Result {}
    enum NotFound implements Result { INSTANCE }
}
