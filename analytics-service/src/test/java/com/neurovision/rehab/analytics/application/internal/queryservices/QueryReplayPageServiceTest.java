package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryReplayPageServiceTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();

    @Test
    void authorizesThroughClinicalBeforeQueryingPersistence() {
        var calls = new ArrayList<String>();
        HistoricalHeatmapReadAuthorizationPort authorization = (session, token) -> {
            calls.add("clinical");
            return HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED;
        };
        ReplayPageQueryPort pages = (identity, gaze, gameplay, size) -> {
            calls.add("mongo");
            return Uni.createFrom().item(ReplayPageQueryPort.NotFound.INSTANCE);
        };
        var service = new QueryReplayPageService(authorization, pages,
                new ReplayCursorCodec("test-only-replay-cursor-signing-key-32-bytes-minimum"));

        var outcome = service.query(SESSION, STREAM, 100, null, null, "access-token").await().indefinitely();

        assertEquals(List.of("clinical", "mongo"), calls);
        assertEquals(QueryReplayPageService.Status.NOT_FOUND, outcome.status());
    }

    @Test
    void neverQueriesMongoWhenClinicalDeniesAccess() {
        var calls = new ArrayList<String>();
        HistoricalHeatmapReadAuthorizationPort authorization = (session, token) -> {
            calls.add("clinical");
            return HistoricalHeatmapReadAuthorizationPort.Decision.DENIED;
        };
        ReplayPageQueryPort pages = (identity, gaze, gameplay, size) -> {
            calls.add("mongo");
            return Uni.createFrom().item(ReplayPageQueryPort.NotFound.INSTANCE);
        };
        var service = new QueryReplayPageService(authorization, pages,
                new ReplayCursorCodec("test-only-replay-cursor-signing-key-32-bytes-minimum"));

        var outcome = service.query(SESSION, STREAM, 100, null, null, "access-token").await().indefinitely();

        assertEquals(List.of("clinical"), calls);
        assertEquals(QueryReplayPageService.Status.FORBIDDEN, outcome.status());
    }
}
