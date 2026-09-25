package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.AnalyticsSummaryQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.HeatmapProjectionQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ReplayPageQueryPort;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsQueryAuthorizationMatrixTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CURSOR_KEY = "synthetic-key-for-query-authorization-matrix";

    @Test
    void everyHistoricalQueryAuthorizesThroughClinicalBeforeItsMongoPort() {
        for (String endpoint : List.of("summary", "heatmap", "gaze-plot", "replay")) {
            var calls = new ArrayList<String>();
            var authorization = (HistoricalHeatmapReadAuthorizationPort) (session, token) -> {
                calls.add("clinical:" + endpoint);
                assertEquals(SESSION, session);
                assertEquals("synthetic-token", token);
                return HistoricalHeatmapReadAuthorizationPort.Decision.DENIED;
            };

            switch (endpoint) {
                case "summary" -> {
                    var service = new QueryAnalyticsSummaryService(authorization, (AnalyticsSummaryQueryPort) identity -> {
                        calls.add("mongo:summary");
                        return Uni.createFrom().item(AnalyticsSummaryQueryPort.NotFound.INSTANCE);
                    });
                    assertEquals(QueryAnalyticsSummaryService.Status.FORBIDDEN,
                            service.query(SESSION, STREAM, "synthetic-token").await().indefinitely().status());
                }
                case "heatmap" -> {
                    var service = new QueryHeatmapProjectionService(authorization, (HeatmapProjectionQueryPort) identity -> {
                        calls.add("mongo:heatmap");
                        return Uni.createFrom().item(HeatmapProjectionQueryPort.NotFound.INSTANCE);
                    });
                    assertEquals(QueryHeatmapProjectionService.Status.FORBIDDEN,
                            service.query(SESSION, STREAM, "synthetic-token").await().indefinitely().status());
                }
                case "gaze-plot" -> {
                    var service = new QueryGazePlotPageService(authorization, (GazePlotPageQueryPort) (identity, cursor, size) -> {
                        calls.add("mongo:gaze-plot");
                        return Uni.createFrom().item(GazePlotPageQueryPort.NotFound.INSTANCE);
                    }, new GazePlotCursorCodec(CURSOR_KEY));
                    assertEquals(QueryGazePlotPageService.Status.FORBIDDEN,
                            service.query(SESSION, STREAM, 10, null, "synthetic-token").await().indefinitely().status());
                }
                case "replay" -> {
                    var service = new QueryReplayPageService(authorization, (ReplayPageQueryPort) (identity, gaze, gameplay, size) -> {
                        calls.add("mongo:replay");
                        return Uni.createFrom().item(ReplayPageQueryPort.NotFound.INSTANCE);
                    }, new ReplayCursorCodec(CURSOR_KEY));
                    assertEquals(QueryReplayPageService.Status.FORBIDDEN,
                            service.query(SESSION, STREAM, 10, null, null, "synthetic-token")
                                    .await().indefinitely().status());
                }
                default -> throw new AssertionError("Unhandled query endpoint: " + endpoint);
            }

            assertEquals(List.of("clinical:" + endpoint), calls,
                    endpoint + " must not query MongoDB after a Clinical denial");
        }
    }
}
