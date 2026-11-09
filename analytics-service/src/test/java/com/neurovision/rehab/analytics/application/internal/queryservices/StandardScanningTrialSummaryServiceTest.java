package com.neurovision.rehab.analytics.application.internal.queryservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort.*;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore.Stored;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import com.neurovision.rehab.analytics.application.internal.queryservices.StandardScanningTrialSummaryService.Status;
import io.smallrye.mutiny.Uni;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StandardScanningTrialSummaryServiceTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final String TOKEN = "synthetic-doctor-token";
    private static final StandardScanningTrialContext CONTEXT = new StandardScanningTrialContext(SESSION, new UUID(0, 2), 1, 500, 3000);

    private static Stored row(String trial, Outcome outcome, long duration, DataOrigin origin) {
        return new Stored(CONTEXT, new StandardScanningTrialResult(1, SESSION, trial, outcome, duration,
                outcome == Outcome.SUCCESS ? 500L : null, origin));
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        Decision decision = new Decision(Permission.PERMITTED, CONTEXT);
        Supplier<Decision> authorize = () -> decision;
        List<Stored> rows = List.of();
        Supplier<Uni<List<Stored>>> read = () -> Uni.createFrom().item(rows);
        DataOrigin selected = DataOrigin.SYNTHETIC;
        final StandardScanningTrialSummaryService service = new StandardScanningTrialSummaryService((session, token) -> {
            calls.add("authorize"); assertEquals(SESSION, session); assertEquals(TOKEN, token); return authorize.get();
        }, (session, origin, limit) -> {
            calls.add("read"); assertEquals(SESSION, session); assertEquals(selected, origin);
            assertEquals(1001, limit); return read.get();
        });
        StandardScanningTrialSummaryService.Result query() {
            return service.query(SESSION, selected, TOKEN).await().atMost(Duration.ofSeconds(1));
        }
    }

    @Test void calculatesTheKnownFixtureAfterFreshAuthorizationWithoutChangingEvidence() {
        var f = new Fixture();
        f.rows = List.of(row("a", Outcome.SUCCESS, 1200, f.selected), row("b", Outcome.SUCCESS, 1800, f.selected),
                row("c", Outcome.TIMEOUT, 3000, f.selected), row("d", Outcome.INTERRUPTED, 900, f.selected));
        var before = List.copyOf(f.rows); var result = f.query();
        assertEquals(Status.AVAILABLE, result.status()); var summary = result.summary();
        assertEquals(4, summary.reportedTrialCount()); assertEquals(2, summary.detectedTargetCount());
        assertEquals(1, summary.omissionCount()); assertEquals(1, summary.interruptedCount());
        assertEquals(2, summary.eligibleLocalizationCount()); assertEquals(new BigDecimal("1500"), summary.medianLocalizationDurationMs().orElseThrow());
        assertEquals(CONTEXT.configurationId(), summary.configurationId()); assertEquals(1, summary.configurationVersion());
        assertEquals(SESSION, summary.therapySessionId()); assertEquals(f.selected, summary.dataOrigin());
        assertEquals("standard-scanning-trial-v1", summary.calculationVersion());
        assertEquals(List.of("authorize", "read"), f.calls); assertEquals(before, f.rows);
    }

    @Test void emptyOrUnsuccessfulResultsHaveNoInventedMedian() {
        var f = new Fixture(); var empty = f.query();
        assertEquals(Status.AVAILABLE, empty.status()); assertEquals(0, empty.summary().reportedTrialCount());
        assertTrue(empty.summary().medianLocalizationDurationMs().isEmpty());
        f.rows = List.of(row("a", Outcome.TIMEOUT, 3000, f.selected), row("b", Outcome.INTERRUPTED, 900, f.selected));
        var summary = f.query().summary(); assertEquals(0, summary.detectedTargetCount());
        assertEquals(1, summary.omissionCount()); assertEquals(1, summary.interruptedCount());
        assertTrue(summary.medianLocalizationDurationMs().isEmpty());
    }

    @Test void identicalDuplicatesHaveOneEffectButConflictingResultsNeverReturnAPartialSummary() {
        var f = new Fixture(); var a = row("opaque trial", Outcome.SUCCESS, 1200, f.selected);
        f.rows = List.of(a, a); assertEquals(1, f.query().summary().reportedTrialCount());
        var b = row("opaque trial", Outcome.SUCCESS, 1800, f.selected);
        for (var rows : List.of(List.of(a, b), List.of(b, a))) {
            f.rows = rows; assertFailure(f.query(), Status.UNAVAILABLE);
        }
    }

    @Test void exactlyOneThousandRowsSucceedAndTheSentinelNeverProducesAPartialSummary() {
        var f = new Fixture(); var rows = new ArrayList<Stored>();
        for (int i = 0; i < 1000; i++) rows.add(row("trial-" + i, Outcome.SUCCESS, 1200, f.selected));
        f.rows = rows; assertEquals(1000, f.query().summary().reportedTrialCount());
        rows.add(row("sentinel", Outcome.SUCCESS, 1200, f.selected));
        assertFailure(f.query(), Status.LIMIT_EXCEEDED);
        rows.add(row("port-violation", Outcome.SUCCESS, 1200, f.selected));
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
                new StandardScanningTrialContext(new UUID(0, 9), CONTEXT.configurationId(), 1, 500, 3000));
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
        var other = new StandardScanningTrialContext(SESSION, new UUID(0, 8), 1, 500, 3000);
        var foreign = new StandardScanningTrialContext(new UUID(0, 9), CONTEXT.configurationId(), 1, 500, 3000);
        var foreignResult = new StandardScanningTrialResult(1, foreign.therapySessionId(), "a", Outcome.SUCCESS, 1200, 500L, f.selected);
        for (var rows : List.of(List.of(row("a", Outcome.SUCCESS, 1200, DataOrigin.PRODUCER_REPORTED)),
                List.of(new Stored(other, row("a", Outcome.SUCCESS, 1200, f.selected).result())),
                List.of(new Stored(foreign, foreignResult)), Arrays.<Stored>asList((Stored) null))) {
            f.rows = rows; assertFailure(f.query(), Status.UNAVAILABLE);
        }
        f.selected = DataOrigin.PRODUCER_REPORTED; f.rows = List.of(row("a", Outcome.SUCCESS, 1200, f.selected));
        assertEquals(f.selected, f.query().summary().dataOrigin());
    }

    @Test void decisionsAndResultsRejectInvalidShapesAndRedactDiagnostics() {
        assertThrows(IllegalArgumentException.class, () -> new Decision(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.PERMITTED, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.DENIED, CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialSummaryService.Result(Status.AVAILABLE, null));
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialSummaryService.Result(null, null));
        var f = new Fixture(); var result = f.query();
        assertThrows(IllegalArgumentException.class, () -> new StandardScanningTrialSummaryService.Result(Status.DENIED, result.summary()));
        assertFalse(result.toString().contains(SESSION.toString()));
        assertFalse(f.decision.toString().contains(CONTEXT.configurationId().toString()));
    }

    private static void assertFailure(StandardScanningTrialSummaryService.Result result, Status expected) {
        assertEquals(expected, result.status()); assertNull(result.summary());
    }
    @Test void invalidSentinelCannotMasqueradeAsLimitExceededOrReturnPartialMetrics() {
        var other = new StandardScanningTrialContext(SESSION, new UUID(0, 8), 1, 500, 3000);
        for (Stored sentinel : Arrays.asList(null,
                row("sentinel", Outcome.SUCCESS, 1200, DataOrigin.PRODUCER_REPORTED),
                new Stored(other, row("sentinel", Outcome.SUCCESS, 1200, DataOrigin.SYNTHETIC).result()))) {
            var f = new Fixture(); var rows = new ArrayList<Stored>();
            for (int i = 0; i < 1000; i++) rows.add(row("trial-" + i, Outcome.SUCCESS, 1200, f.selected));
            rows.add(sentinel); f.rows = rows; var before = new ArrayList<>(rows);
            assertFailure(f.query(), Status.UNAVAILABLE);
            assertEquals(List.of("authorize", "read"), f.calls); assertEquals(before, f.rows);
        }
    }
    @Test void freshPermitContextIsUsedForEachSubscriptionInsteadOfCachedFrozenPolicy() {
        var f = new Fixture(); f.rows = List.of(row("a", Outcome.SUCCESS, 1200, f.selected));
        var pending = f.service.query(SESSION, f.selected, TOKEN);
        assertEquals(Status.AVAILABLE, pending.await().atMost(Duration.ofSeconds(1)).status());
        var before = List.copyOf(f.rows);
        f.decision = new Decision(Permission.PERMITTED,
                new StandardScanningTrialContext(SESSION, CONTEXT.configurationId(), 2, 500, 3000));
        assertFailure(pending.await().atMost(Duration.ofSeconds(1)), Status.UNAVAILABLE);
        assertEquals(List.of("authorize", "read", "authorize", "read"), f.calls); assertEquals(before, f.rows);
    }
}
