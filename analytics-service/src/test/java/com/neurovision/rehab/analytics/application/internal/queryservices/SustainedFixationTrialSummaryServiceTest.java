package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService.Status;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.Outcome;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SustainedFixationTrialSummaryServiceTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID CONFIG = UUID.randomUUID();
    private static final String TOKEN = "original-doctor-token";
    private static final DataOrigin ORIGIN = DataOrigin.SYNTHETIC;
    private static final SustainedFixationTrialContext CONTEXT =
            new SustainedFixationTrialContext(SESSION, CONFIG, 2, 1000, 3000);

    @Test
    void authorizesBeforeReadingAndSummarizesExactEvidence() {
        var calls = new ArrayList<String>();
        var success = success("first");
        var service = new SustainedFixationTrialSummaryService((session, token) -> {
            assertEquals(SESSION, session);
            assertEquals(TOKEN, token);
            calls.add("authorize");
            return permitted();
        }, (session, origin, limit) -> {
            assertEquals(List.of("authorize"), calls);
            assertEquals(SESSION, session);
            assertEquals(ORIGIN, origin);
            assertEquals(1001, limit);
            calls.add("read");
            return Uni.createFrom().item(List.of(success, success,
                    row(CONTEXT, ORIGIN, "second", Outcome.TIMEOUT, 3000, 1100, 600),
                    row(CONTEXT, ORIGIN, "third", Outcome.INTERRUPTED, 900, 400, 400)));
        });
        var query = service.query(SESSION, ORIGIN, TOKEN);
        assertTrue(calls.isEmpty());
        var result = await(query);
        assertEquals(List.of("authorize", "read"), calls);
        assertEquals(Status.AVAILABLE, result.status());
        var summary = result.summary();
        assertEquals("sustained-fixation-trial-v1", summary.calculationVersion());
        assertEquals(SESSION, summary.therapySessionId());
        assertEquals(CONFIG, summary.configurationId());
        assertEquals(2, summary.configurationVersion());
        assertEquals(ORIGIN, summary.dataOrigin());
        assertEquals(3, summary.reportedTrialCount());
        assertEquals(2700, summary.totalInsideTargetMs());
        assertEquals(1, summary.completedTargetCount());
        assertEquals("SustainedFixationSummaryResult[AVAILABLE]", result.toString());
    }

    @Test
    void reportsAuthorizedEmptyEvidence() {
        var result = query(rows(List.of()));
        assertEquals(Status.AVAILABLE, result.status());
        assertEquals(0, result.summary().reportedTrialCount());
        assertEquals(0, result.summary().totalInsideTargetMs());
        assertEquals(0, result.summary().completedTargetCount());
    }

    @Test
    void rejectsInvalidInputsWithoutCallingEitherPort() {
        var service = new SustainedFixationTrialSummaryService((session, token) -> fail("authorization called"),
                (session, origin, limit) -> fail("reader called"));
        assertStatus(Status.INVALID_TOKEN, await(service.query(SESSION, ORIGIN, null)));
        assertStatus(Status.INVALID_TOKEN, await(service.query(SESSION, ORIGIN, "  ")));
        assertStatus(Status.INVALID_QUERY, await(service.query(null, ORIGIN, TOKEN)));
        assertStatus(Status.INVALID_QUERY, await(service.query(SESSION, null, TOKEN)));
    }

    @Test
    void rejectsDeniedInvalidAndUnavailableAuthorizationWithoutReading() {
        assertStatus(Status.DENIED, query(auth(new Decision(Permission.DENIED, null))));
        assertStatus(Status.INVALID_TOKEN, query(auth(new Decision(Permission.INVALID_TOKEN, null))));
        assertStatus(Status.UNAVAILABLE, query(auth(new Decision(Permission.UNAVAILABLE, null))));
        assertStatus(Status.UNAVAILABLE, query(auth(null)));
        assertStatus(Status.UNAVAILABLE, query(auth(new Decision(Permission.PERMITTED,
                new SustainedFixationTrialContext(UUID.randomUUID(), CONFIG, 2, 1000, 3000)))));
        assertStatus(Status.UNAVAILABLE, query(new SustainedFixationTrialSummaryService((session, token) -> {
            throw new IllegalStateException("authorization failure");
        }, (session, origin, limit) -> fail("reader called"))));
    }

    @Test
    void reauthorizesOnResubscriptionBeforeReadingAgain() {
        var authorizations = new AtomicInteger();
        var reads = new AtomicInteger();
        var service = new SustainedFixationTrialSummaryService((session, token) ->
                authorizations.incrementAndGet() == 1 ? permitted() : new Decision(Permission.DENIED, null),
                (session, origin, limit) -> {
                    reads.incrementAndGet();
                    return Uni.createFrom().item(List.of());
                });
        var query = service.query(SESSION, ORIGIN, TOKEN);
        assertEquals(Status.AVAILABLE, await(query).status());
        assertStatus(Status.DENIED, await(query));
        assertEquals(2, authorizations.get());
        assertEquals(1, reads.get());
    }

    @Test
    void acceptsTheCapButDoesNotReturnPartialResultsAboveIt() {
        var atCap = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> success("trial-" + i)).toList();
        var result = query(rows(atCap));
        assertEquals(Status.AVAILABLE, result.status());
        assertEquals(1000, result.summary().completedTargetCount());
        var overCap = new ArrayList<>(atCap);
        overCap.add(success("sentinel"));
        assertStatus(Status.LIMIT_EXCEEDED, query(rows(overCap)));
        overCap.add(success("over-limit"));
        assertStatus(Status.UNAVAILABLE, query(rows(overCap)));
    }

    @Test
    void rejectsForeignOrCorruptEvidenceIncludingTheSentinel() {
        var foreignConfig = new SustainedFixationTrialContext(SESSION, CONFIG, 3, 1000, 3000);
        var foreignSession = new SustainedFixationTrialContext(UUID.randomUUID(), CONFIG, 2, 1000, 3000);
        assertStatus(Status.UNAVAILABLE, query(rows(List.of(row(foreignConfig, ORIGIN,
                "foreign-config", Outcome.SUCCESS, 1500, 1200, 1000)))));
        assertStatus(Status.UNAVAILABLE, query(rows(List.of(row(foreignSession, ORIGIN,
                "foreign-session", Outcome.SUCCESS, 1500, 1200, 1000)))));
        var overCap = new ArrayList<>(Collections.nCopies(1000, success("matching")));
        overCap.add(row(CONTEXT, DataOrigin.PRODUCER_REPORTED,
                "wrong-origin", Outcome.SUCCESS, 1500, 1200, 1000));
        assertStatus(Status.UNAVAILABLE, query(rows(overCap)));
        assertStatus(Status.UNAVAILABLE, query(rows(Collections.singletonList(null))));
    }

    @Test
    void reportsReaderAndCalculatorFailuresWithoutAnEmptySummary() {
        assertStatus(Status.UNAVAILABLE, query(rows(null)));
        assertStatus(Status.UNAVAILABLE, query(new SustainedFixationTrialSummaryService(
                (session, token) -> permitted(), (session, origin, limit) -> null)));
        assertStatus(Status.UNAVAILABLE, query(new SustainedFixationTrialSummaryService(
                (session, token) -> permitted(), (session, origin, limit) -> {
                    throw new IllegalStateException("reader failure");
                })));
        assertStatus(Status.UNAVAILABLE, query(new SustainedFixationTrialSummaryService(
                (session, token) -> permitted(), (session, origin, limit) ->
                        Uni.createFrom().failure(new IllegalStateException("asynchronous reader failure")))));
        assertStatus(Status.UNAVAILABLE, query(rows(List.of(success("conflict"),
                row(CONTEXT, ORIGIN, "conflict", Outcome.TIMEOUT, 3000, 100, 100)))));
    }

    private static SustainedFixationTrialSummaryService auth(Decision decision) {
        return new SustainedFixationTrialSummaryService((session, token) -> decision,
                (session, origin, limit) -> fail("reader called"));
    }

    private static SustainedFixationTrialSummaryService rows(List<Stored> rows) {
        return new SustainedFixationTrialSummaryService((session, token) -> permitted(),
                (session, origin, limit) -> Uni.createFrom().item(rows));
    }

    private static Decision permitted() {
        return new Decision(Permission.PERMITTED, CONTEXT);
    }

    private static Stored success(String id) {
        return row(CONTEXT, ORIGIN, id, Outcome.SUCCESS, 1500, 1200, 1000);
    }

    private static Stored row(SustainedFixationTrialContext context, DataOrigin origin, String id, Outcome outcome,
            long elapsed, long inside, long longest) {
        return new Stored(context, new SustainedFixationTrialResult(1, context.therapySessionId(), id, outcome,
                elapsed, inside, longest, origin));
    }

    private static SustainedFixationTrialSummaryService.Result query(SustainedFixationTrialSummaryService service) {
        return await(service.query(SESSION, ORIGIN, TOKEN));
    }

    private static SustainedFixationTrialSummaryService.Result await(
            Uni<SustainedFixationTrialSummaryService.Result> query) {
        return query.await().atMost(Duration.ofSeconds(1));
    }

    private static void assertStatus(Status expected, SustainedFixationTrialSummaryService.Result result) {
        assertEquals(expected, result.status());
        assertNull(result.summary());
    }
}
