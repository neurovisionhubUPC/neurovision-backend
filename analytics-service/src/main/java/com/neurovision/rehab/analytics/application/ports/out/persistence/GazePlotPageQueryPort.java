package com.neurovision.rehab.analytics.application.ports.out.persistence;

import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import io.smallrye.mutiny.Uni;

public interface GazePlotPageQueryPort {
    Uni<Result> loadPage(AnalyticsProjectionIdentity identity, GazePlotCursor cursor, int pageSize);

    sealed interface Result permits Found, NotFound {}
    record Found(GazePlotPage page) implements Result {}
    enum NotFound implements Result { INSTANCE }
}
