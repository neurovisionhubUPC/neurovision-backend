package com.neurovision.rehab.analytics.interfaces.rest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neurovision.rehab.analytics.application.internal.queryservices.QueryAnalyticsSummaryService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryGazePlotPageService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryReplayPageService;
import com.neurovision.rehab.analytics.application.internal.queryservices.QueryAnalyticsSummaryService.Status;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsCalculationProfile;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsSummaryProjection;
import com.neurovision.rehab.analytics.domain.model.valueobjects.BaselineStreamSummary;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLane;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayLanePage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.ReplayPage;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsQueryControllerContractTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void mapsEachEndpointFailureToItsExistingStatusAndCode() {
        assertError(AnalyticsSummaryController.toResponse(new QueryAnalyticsSummaryService.Outcome(Status.UNAUTHENTICATED, null)),
                401, "UNAUTHENTICATED");
        assertError(AnalyticsSummaryController.toResponse(new QueryAnalyticsSummaryService.Outcome(Status.FORBIDDEN, null)),
                403, "SUMMARY_READ_FORBIDDEN");
        assertError(AnalyticsSummaryController.toResponse(new QueryAnalyticsSummaryService.Outcome(Status.NOT_FOUND, null)),
                404, "SUMMARY_NOT_FOUND");
        assertError(AnalyticsSummaryController.toResponse(new QueryAnalyticsSummaryService.Outcome(Status.UNAVAILABLE, null)),
                503, "SUMMARY_READ_UNAVAILABLE");

        assertError(HeatmapProjectionController.toResponse(new QueryHeatmapProjectionService.Outcome(
                QueryHeatmapProjectionService.Status.UNAUTHENTICATED, null)), 401, "UNAUTHENTICATED");
        assertError(HeatmapProjectionController.toResponse(new QueryHeatmapProjectionService.Outcome(
                QueryHeatmapProjectionService.Status.FORBIDDEN, null)), 403, "HEATMAP_READ_FORBIDDEN");
        assertError(HeatmapProjectionController.toResponse(new QueryHeatmapProjectionService.Outcome(
                QueryHeatmapProjectionService.Status.NOT_FOUND, null)), 404, "HEATMAP_NOT_FOUND");
        assertError(HeatmapProjectionController.toResponse(new QueryHeatmapProjectionService.Outcome(
                QueryHeatmapProjectionService.Status.UNAVAILABLE, null)), 503, "HEATMAP_READ_UNAVAILABLE");

        assertError(GazePlotPageController.toResponse(new QueryGazePlotPageService.Outcome(
                QueryGazePlotPageService.Status.UNAUTHENTICATED, null)), 401, "UNAUTHENTICATED");
        assertError(GazePlotPageController.toResponse(new QueryGazePlotPageService.Outcome(
                QueryGazePlotPageService.Status.FORBIDDEN, null)), 403, "GAZE_PLOT_READ_FORBIDDEN");
        assertError(GazePlotPageController.toResponse(new QueryGazePlotPageService.Outcome(
                QueryGazePlotPageService.Status.NOT_FOUND, null)), 404, "GAZE_PLOT_NOT_FOUND");
        assertError(GazePlotPageController.toResponse(new QueryGazePlotPageService.Outcome(
                QueryGazePlotPageService.Status.UNAVAILABLE, null)), 503, "GAZE_PLOT_READ_UNAVAILABLE");

        assertError(ReplayPageController.toResponse(new QueryReplayPageService.Outcome(
                QueryReplayPageService.Status.UNAUTHENTICATED, null, null, null)), 401, "UNAUTHENTICATED");
        assertError(ReplayPageController.toResponse(new QueryReplayPageService.Outcome(
                QueryReplayPageService.Status.FORBIDDEN, null, null, null)), 403, "REPLAY_READ_FORBIDDEN");
        assertError(ReplayPageController.toResponse(new QueryReplayPageService.Outcome(
                QueryReplayPageService.Status.NOT_FOUND, null, null, null)), 404, "REPLAY_NOT_FOUND");
        assertError(ReplayPageController.toResponse(new QueryReplayPageService.Outcome(
                QueryReplayPageService.Status.UNAVAILABLE, null, null, null)), 503, "REPLAY_READ_UNAVAILABLE");
    }

    @Test
    void successDtosRetainVersionAndCompletenessMetadata() {
        var summary = AnalyticsSummaryController.toResponse(new QueryAnalyticsSummaryService.Outcome(
                QueryAnalyticsSummaryService.Status.OK, summaryProjection()));
        var summaryDto = assertInstanceOf(AnalyticsSummaryResponse.class, summary.getEntity());
        assertEquals(1, summaryDto.schemaVersion());
        assertEquals("baseline-v1", summaryDto.calculationVersion());
        assertEquals(2, summaryDto.revision());
        assertEquals(0, summaryDto.completeness().minimumSequence());
        assertEquals(1, summaryDto.completeness().maximumSequence());
        assertTrue(summaryDto.completeness().unknownSessionBoundary());

        var heatmap = HeatmapProjectionController.toResponse(new QueryHeatmapProjectionService.Outcome(
                QueryHeatmapProjectionService.Status.OK,
                com.neurovision.rehab.analytics.application.internal.queryservices.QueryHeatmapProjectionServiceTest.projection()));
        var heatmapDto = assertInstanceOf(HeatmapProjectionResponse.class, heatmap.getEntity());
        assertEquals(1, heatmapDto.contractVersion());

        var identity = new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");
        var metadata = new ReplayMetadata(identity, 2, 1, 0, 1, List.of(), true);
        var replayPage = new ReplayPage(metadata,
                new ReplayLanePage(ReplayLane.GAZE, List.of(), Optional.empty(), List.of()),
                new ReplayLanePage(ReplayLane.GAMEPLAY, List.of(), Optional.empty(), List.of()));
        var replay = ReplayPageController.toResponse(new QueryReplayPageService.Outcome(
                QueryReplayPageService.Status.OK, replayPage, null, null));
        var replayDto = assertInstanceOf(com.neurovision.rehab.analytics.interfaces.rest.transform.ReplayPageRestAssembler
                .ReplayResponse.class, replay.getEntity());
        assertEquals(1, replayDto.contractVersion());
        assertEquals(2, replayDto.projectionRevision());
        assertEquals(1, replayDto.sourceSchemaVersion());
        assertTrue(replayDto.unknownSessionBoundary());
    }

    private static void assertError(Response response, int status, String code) {
        assertEquals(status, response.getStatus());
        assertEquals(java.util.Map.of("code", code), response.getEntity());
    }

    private static AnalyticsSummaryProjection summaryProjection() {
        var summary = new BaselineStreamSummary(AnalyticsCalculationProfile.BASELINE_V1, 1, SESSION, STREAM,
                0, 1, List.of(), 0, true, Optional.empty(),
                new BaselineStreamSummary.SampleCounts(0, 0), BaselineStreamSummary.NumericSummary.empty(),
                Collections.nCopies(10, 0L),
                new BaselineStreamSummary.Heatmap(10, 10, Collections.nCopies(100, 0L)),
                new BaselineStreamSummary.Coverage(Optional.empty(), 0, 0),
                new BaselineStreamSummary.PathMetrics(0, BaselineStreamSummary.NumericSummary.empty(), 0),
                new BaselineStreamSummary.GameplayMetrics(BaselineStreamSummary.NumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty(),
                        BaselineStreamSummary.OrderedNumericSummary.empty()));
        return new AnalyticsSummaryProjection(new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1"), 2, summary);
    }
}
