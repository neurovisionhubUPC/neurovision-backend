package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.neurovision.rehab.analytics.application.ports.out.authorization.HistoricalHeatmapReadAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.Found;
import com.neurovision.rehab.analytics.application.ports.out.persistence.GazePlotPageQueryPort.NotFound;
import com.neurovision.rehab.analytics.domain.model.valueobjects.AnalyticsProjectionIdentity;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotCursor;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotMetadata;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPage;
import com.neurovision.rehab.analytics.domain.model.valueobjects.GazePlotPoint;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryGazePlotPageServiceTest {
    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STREAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TEST_KEY = "synthetic-test-key-for-gaze-plot-cursor-signing-only";
    private final AtomicInteger pageReads = new AtomicInteger();
    private HistoricalHeatmapReadAuthorizationPort.Decision decision =
            HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED;
    private GazePlotPageQueryPort.Result result = new Found(page());
    private RuntimeException readFailure;

    @Test
    void clinicalDenialsAndInvalidTokensDoNotReadTheGazePagePort() {
        var service = service();
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.DENIED;
        assertEquals(QueryGazePlotPageService.Status.FORBIDDEN, query(service, null).status());
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.INVALID_TOKEN;
        assertEquals(QueryGazePlotPageService.Status.UNAUTHENTICATED, query(service, null).status());
        decision = HistoricalHeatmapReadAuthorizationPort.Decision.UNAVAILABLE;
        assertEquals(QueryGazePlotPageService.Status.UNAVAILABLE, query(service, null).status());
        assertEquals(0, pageReads.get());
    }

    @Test
    void passesBoundIdentityCursorAndRequestedBoundToPersistenceAfterPermit() {
        var cursor = new GazePlotCursor(2, identity(), 2, 1, 1, 0, 0,
                Instant.parse("2026-09-24T10:00:00Z"));
        var codec = new GazePlotCursorCodec(TEST_KEY);
        String token = codec.encode(cursor);
        var decoded = codec.decode(token, identity());
        assertEquals(cursor, decoded);

        var observed = new AtomicInteger();
        var service = new QueryGazePlotPageService((session, accessToken) -> {
            assertEquals(SESSION, session);
            assertEquals("original-jwt", accessToken);
            return HistoricalHeatmapReadAuthorizationPort.Decision.PERMITTED;
        }, (requestedIdentity, requestedCursor, pageSize) -> {
            assertEquals(identity(), requestedIdentity);
            assertEquals(cursor, requestedCursor);
            assertEquals(25, pageSize);
            observed.incrementAndGet();
            return Uni.createFrom().item(result);
        }, codec);

        assertEquals(QueryGazePlotPageService.Status.OK,
                service.query(SESSION, STREAM, 25, token, "original-jwt").await().indefinitely().status());
        assertEquals(1, observed.get());
    }

    @Test
    void malformedOrCrossStreamCursorIsRejectedBeforeAuthorizationOrMongo() {
        var otherIdentity = new AnalyticsProjectionIdentity(SESSION, UUID.randomUUID(), "baseline-v1");
        String otherStream = new GazePlotCursorCodec(TEST_KEY).encode(
                new GazePlotCursor(2, otherIdentity, 2, 1, 0, 0, 0,
                        Instant.parse("2026-09-24T10:00:00Z")));
        var service = service();
        assertEquals(QueryGazePlotPageService.Status.INVALID_REQUEST,
                query(service, "not-base64").status());
        assertEquals(QueryGazePlotPageService.Status.INVALID_REQUEST,
                query(service, otherStream).status());
        assertEquals(0, pageReads.get());
    }

    @Test
    void missingAndUnavailableProjectionResultsStayDistinctAndBounded() {
        result = NotFound.INSTANCE;
        var missing = query(service(), null);
        assertEquals(QueryGazePlotPageService.Status.NOT_FOUND, missing.status());
        assertNull(missing.page());
        assertEquals(1, pageReads.get());

        readFailure = new IllegalStateException("stale projection revision");
        var unavailable = query(service(), null);
        assertEquals(QueryGazePlotPageService.Status.UNAVAILABLE, unavailable.status());
        assertEquals(2, pageReads.get());
    }

    @Test
    void invalidBoundsAndMissingBearerNeverCallExternalPorts() {
        var service = service();
        assertEquals(QueryGazePlotPageService.Status.INVALID_REQUEST,
                service.query(SESSION, STREAM, 501, null, "original-jwt").await().indefinitely().status());
        assertEquals(QueryGazePlotPageService.Status.UNAUTHENTICATED,
                service.query(SESSION, STREAM, 20, null, " ").await().indefinitely().status());
        assertEquals(0, pageReads.get());
    }

    @Test
    void authorizedPageOutcomeCarriesOnlyTheReturnedObservedPage() {
        var expected = ((Found) result).page();
        var outcome = query(service(), null);
        assertEquals(QueryGazePlotPageService.Status.OK, outcome.status());
        assertSame(expected, outcome.page());
    }

    private QueryGazePlotPageService service() {
        return new QueryGazePlotPageService((session, token) -> decision, (requestedIdentity, cursor, size) -> {
            pageReads.incrementAndGet();
            if (readFailure != null) return Uni.createFrom().failure(readFailure);
            return Uni.createFrom().item(result);
        }, new GazePlotCursorCodec(TEST_KEY));
    }

    private static QueryGazePlotPageService.Outcome query(QueryGazePlotPageService service, String cursor) {
        return service.query(SESSION, STREAM, 20, cursor, "original-jwt").await().indefinitely();
    }

    private static AnalyticsProjectionIdentity identity() {
        return new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1");
    }

    private static GazePlotPage page() {
        var metadata = new GazePlotMetadata(identity(), 2, 1, 0, 0, 0, List.of(), 0,
                false, 1, Optional.of(new GazePlotMetadata.TimeRange(
                        Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:00Z"))));
        var point = new GazePlotPoint(0, 0, Instant.parse("2026-09-24T10:00:00Z"), .25, .75, .9, true);
        return new GazePlotPage(metadata, List.of(point), Optional.empty());
    }
}
