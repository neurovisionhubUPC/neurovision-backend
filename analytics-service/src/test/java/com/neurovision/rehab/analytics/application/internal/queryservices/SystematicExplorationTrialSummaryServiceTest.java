package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort.*;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.DataOrigin;
import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService.Status;
import io.smallrye.mutiny.Uni;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SystematicExplorationTrialSummaryServiceTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final String TOKEN = "synthetic-doctor-token";
    private static final SystematicExplorationTrialContext CONTEXT = new SystematicExplorationTrialContext(SESSION, new UUID(0, 2), 1, 500, 3000);

    private static Stored row(String trial, Outcome outcome, long duration, DataOrigin origin) {
        var visits = outcome == Outcome.COMPLETED
                ? Arrays.stream(Quadrant.values()).map(q -> new Visit(q, duration, 500)).toList()
                : List.<Visit>of();
        return new Stored(CONTEXT, new SystematicExplorationTrialResult(SESSION, trial, origin, outcome,
                outcome == Outcome.COMPLETED ? duration * 4 : duration, visits));
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        Decision decision = new Decision(Permission.PERMITTED, CONTEXT);
        Supplier<Decision> authorize = () -> decision;
        List<Stored> rows = List.of();
        Supplier<Uni<List<Stored>>> read = () -> Uni.createFrom().item(rows);
        DataOrigin selected = DataOrigin.SYNTHETIC;
        final SystematicExplorationTrialSummaryService service = new SystematicExplorationTrialSummaryService((session, token) -> {
            calls.add("authorize"); assertEquals(SESSION, session); assertEquals(TOKEN, token); return authorize.get();
        }, (session, origin, limit) -> {
            calls.add("read"); assertEquals(SESSION, session); assertEquals(selected, origin);
            assertEquals(1001, limit); return read.get();
        });
        SystematicExplorationTrialSummaryService.Result query() {
            return service.query(SESSION, selected, TOKEN).await().atMost(Duration.ofSeconds(1));
        }
    }

    @Test void calculatesTheKnownFixtureAfterFreshAuthorizationWithoutChangingEvidence() {
        var f = new Fixture();
        f.rows = List.of(row("a", Outcome.COMPLETED, 1200, f.selected), row("b", Outcome.COMPLETED, 1800, f.selected),
                row("c", Outcome.TIMED_OUT, 3000, f.selected), row("d", Outcome.INTERRUPTED, 900, f.selected));
        var before = List.copyOf(f.rows); var result = f.query();
        assertEquals(Status.AVAILABLE, result.status()); var summary = result.summary();
        assertEquals(4, summary.reportedTrialCount());
        assertEquals(List.of("a", "b", "c", "d"), summary.traversals().stream().map(t -> t.trialId()).toList());
        assertEquals(List.of(4, 4, 0, 0), summary.traversals().stream().map(t -> t.visitedRegionCount()).toList());
        assertEquals(List.of(100, 100, 0, 0), summary.traversals().stream().map(t -> t.coveragePercent()).toList());
        assertEquals(List.of(4800L, 7200L, 3000L, 900L), summary.traversals().stream().map(t -> t.elapsedMs()).toList());
        assertEquals(List.of(Outcome.COMPLETED, Outcome.COMPLETED, Outcome.TIMED_OUT, Outcome.INTERRUPTED),
                summary.traversals().stream().map(t -> t.outcome()).toList());
        assertEquals(CONTEXT.configurationId(), summary.configurationId()); assertEquals(1, summary.configurationVersion());
        assertEquals(SESSION, summary.therapySessionId()); assertEquals(f.selected, summary.dataOrigin());
        assertEquals("systematic-exploration-trial-v1", summary.calculationVersion());
        assertEquals(List.of("authorize", "read"), f.calls); assertEquals(before, f.rows);
    }

    @Test void emptyResultsDoNotManufactureAZeroTraversalAndPartialVisitsKeepTheirCoverage() {
        var f = new Fixture(); var empty = f.query();
        assertEquals(Status.AVAILABLE, empty.status()); assertEquals(0, empty.summary().reportedTrialCount());
        assertTrue(empty.summary().traversals().isEmpty());
        var visits = List.of(new Visit(Quadrant.TOP_LEFT, 800, 500), new Visit(Quadrant.TOP_RIGHT, 900, 500));
        f.rows = List.of(new Stored(CONTEXT, new SystematicExplorationTrialResult(SESSION, "partial", f.selected,
                Outcome.TIMED_OUT, 4700, visits)));
        var summary = f.query().summary(); assertEquals(1, summary.reportedTrialCount());
        var traversal = summary.traversals().getFirst();
        assertEquals(2, traversal.visitedRegionCount()); assertEquals(50, traversal.coveragePercent());
        assertEquals(4700, traversal.elapsedMs()); assertEquals(Outcome.TIMED_OUT, traversal.outcome());
        assertThrows(UnsupportedOperationException.class, () -> summary.traversals().clear());
    }

    @Test void identicalDuplicatesHaveOneEffectButConflictingResultsNeverReturnAPartialSummary() {
        var f = new Fixture(); var a = row("opaque trial", Outcome.COMPLETED, 1200, f.selected);
        f.rows = List.of(a, a); assertEquals(1, f.query().summary().reportedTrialCount());
        var b = row("opaque trial", Outcome.COMPLETED, 1800, f.selected);
        for (var rows : List.of(List.of(a, b), List.of(b, a))) {
            f.rows = rows; assertFailure(f.query(), Status.UNAVAILABLE);
        }
    }

    @Test void exactlyOneThousandRowsSucceedAndTheSentinelNeverProducesAPartialSummary() {
        var f = new Fixture(); var rows = new ArrayList<Stored>();
        for (int i = 0; i < 1000; i++) rows.add(row("trial-" + i, Outcome.COMPLETED, 1200, f.selected));
        f.rows = rows; assertEquals(1000, f.query().summary().reportedTrialCount());
        rows.add(row("sentinel", Outcome.COMPLETED, 1200, f.selected));
        assertFailure(f.query(), Status.LIMIT_EXCEEDED);
        rows.add(row("port-violation", Outcome.COMPLETED, 1200, f.selected));
        assertFailure(f.query(), Status.UNAVAILABLE);
    }

    @Test void eachSubscriptionReauthorizesAndRevocationPreventsAnyFurtherRead() {
        var f = new Fixture(); var query = f.service.query(SESSION, f.selected, TOKEN);
        assertTrue(f.calls.isEmpty()); assertEquals(Status.AVAILABLE, query.await().indefinitely().status());
        f.decision = new Decision(Permission.DENIED, null);
        assertFailure(query.await().indefinitely(), Status.DENIED);
        assertEquals(List.of("authorize", "read", "authorize"), f.calls);
    }

    @Test void rejectedOrUnavailableAuthorizationNeverReadsEvidence() {
        for (var permission : List.of(Permission.DENIED, Permission.INVALID_TOKEN, Permission.UNAVAILABLE)) {
            var f = new Fixture(); f.decision = new Decision(permission, null);
            assertFailure(f.query(), Status.valueOf(permission.name())); assertEquals(List.of("authorize"), f.calls);
        }
        for (boolean throwing : List.of(false, true)) {
            var f = new Fixture(); f.authorize = () -> { if (throwing) throw new IllegalStateException("private"); return null; };
            assertFailure(f.query(), Status.UNAVAILABLE); assertEquals(List.of("authorize"), f.calls);
        }
        var f = new Fixture(); f.decision = new Decision(Permission.PERMITTED,
                new SystematicExplorationTrialContext(new UUID(0, 9), CONTEXT.configurationId(), 1, 500, 3000));
        assertFailure(f.query(), Status.UNAVAILABLE); assertEquals(List.of("authorize"), f.calls);
    }

    @Test void invalidInputsDoNotCallEitherPort() {
        var f = new Fixture();
        assertFailure(f.service.query(null, f.selected, TOKEN).await().indefinitely(), Status.INVALID_QUERY);
        assertFailure(f.service.query(SESSION, null, TOKEN).await().indefinitely(), Status.INVALID_QUERY);
        for (String token : Arrays.asList(null, "", " "))
            assertFailure(f.service.query(SESSION, f.selected, token).await().indefinitely(), Status.INVALID_TOKEN);
        assertTrue(f.calls.isEmpty());
    }

    @Test void storageFailuresAndNullResponsesAreNotEmptyEvidenceAndAreNotRetried() {
        for (int mode = 0; mode < 4; mode++) {
            var f = new Fixture(); final int selected = mode;
            f.read = () -> switch (selected) {
                case 0 -> throw new IllegalStateException("private");
                case 1 -> null;
                case 2 -> Uni.createFrom().nullItem();
                default -> Uni.createFrom().failure(new IllegalStateException("private"));
            };
            assertFailure(f.query(), Status.UNAVAILABLE); assertEquals(List.of("authorize", "read"), f.calls);
        }
    }

    @Test void wrongSessionOriginContextOrNullRowFailsClosed() {
        var f = new Fixture();
        var other = new SystematicExplorationTrialContext(SESSION, new UUID(0, 8), 1, 500, 3000);
        var foreign = new SystematicExplorationTrialContext(new UUID(0, 9), CONTEXT.configurationId(), 1, 500, 3000);
        var foreignResult = new SystematicExplorationTrialResult(foreign.therapySessionId(), "a", f.selected, Outcome.INTERRUPTED, 900, List.of());
        for (var rows : List.of(List.of(row("a", Outcome.COMPLETED, 1200, DataOrigin.PRODUCER_REPORTED)),
                List.of(new Stored(other, row("a", Outcome.COMPLETED, 1200, f.selected).result())),
                List.of(new Stored(foreign, foreignResult)), Arrays.<Stored>asList((Stored) null))) {
            f.rows = rows; assertFailure(f.query(), Status.UNAVAILABLE);
        }
        f.selected = DataOrigin.PRODUCER_REPORTED; f.rows = List.of(row("a", Outcome.COMPLETED, 1200, f.selected));
        assertEquals(f.selected, f.query().summary().dataOrigin());
    }

    @Test void decisionsAndResultsRejectInvalidShapesAndRedactDiagnostics() {
        assertThrows(IllegalArgumentException.class, () -> new Decision(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.PERMITTED, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.DENIED, CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialSummaryService.Result(Status.AVAILABLE, null));
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialSummaryService.Result(null, null));
        var f = new Fixture(); var result = f.query();
        assertThrows(IllegalArgumentException.class, () -> new SystematicExplorationTrialSummaryService.Result(Status.DENIED, result.summary()));
        assertFalse(result.toString().contains(SESSION.toString()));
        assertFalse(f.decision.toString().contains(CONTEXT.configurationId().toString()));
    }

    private static void assertFailure(SystematicExplorationTrialSummaryService.Result result, Status expected) {
        assertEquals(expected, result.status()); assertNull(result.summary());
    }
    @Test void invalidSentinelCannotMasqueradeAsLimitExceededOrReturnPartialMetrics() {
        var other = new SystematicExplorationTrialContext(SESSION, new UUID(0, 8), 1, 500, 3000);
        for (Stored sentinel : Arrays.asList(null,
                row("sentinel", Outcome.COMPLETED, 1200, DataOrigin.PRODUCER_REPORTED),
                new Stored(other, row("sentinel", Outcome.COMPLETED, 1200, DataOrigin.SYNTHETIC).result()))) {
            var f = new Fixture(); var rows = new ArrayList<Stored>();
            for (int i = 0; i < 1000; i++) rows.add(row("trial-" + i, Outcome.COMPLETED, 1200, f.selected));
            rows.add(sentinel); f.rows = rows; var before = new ArrayList<>(rows);
            assertFailure(f.query(), Status.UNAVAILABLE);
            assertEquals(List.of("authorize", "read"), f.calls); assertEquals(before, f.rows);
        }
    }
    @Test void freshPermitContextIsUsedForEachSubscriptionInsteadOfCachedFrozenPolicy() {
        var f = new Fixture(); f.rows = List.of(row("a", Outcome.COMPLETED, 1200, f.selected));
        var pending = f.service.query(SESSION, f.selected, TOKEN);
        assertEquals(Status.AVAILABLE, pending.await().atMost(Duration.ofSeconds(1)).status());
        var before = List.copyOf(f.rows);
        f.decision = new Decision(Permission.PERMITTED,
                new SystematicExplorationTrialContext(SESSION, CONTEXT.configurationId(), 2, 500, 3000));
        assertFailure(pending.await().atMost(Duration.ofSeconds(1)), Status.UNAVAILABLE);
        assertEquals(List.of("authorize", "read", "authorize", "read"), f.calls); assertEquals(before, f.rows);
    }
}
