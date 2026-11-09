package com.neurovision.rehab.analytics.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore.Stored;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore.WriteResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReceiveStandardScanningTrialResultServiceTest {
    private static final UUID SESSION = new UUID(0, 1), CONFIG = new UUID(0, 2);
    private static final String TOKEN = "synthetic-original-token";
    private static final StandardScanningTrialContext CONTEXT = new StandardScanningTrialContext(SESSION, CONFIG, 3, 500, 3000);
    private static StandardScanningTrialResult result(long duration) {
        return new StandardScanningTrialResult(1, SESSION, "synthetic-trial", Outcome.SUCCESS, duration, 500L, DataOrigin.SYNTHETIC);
    }
    private static final class Fixture implements StandardScanningTrialResultStore {
        final List<String> calls = new ArrayList<>();
        Decision permission = new Decision(Permission.PERMITTED, CONTEXT, true);
        Stored saved;
        Function<UUID, Decision> authorize = id -> permission;
        Function<String, Uni<Optional<Stored>>> read = id -> Uni.createFrom().item(Optional.ofNullable(saved));
        Function<Stored, Uni<WriteResult>> write = candidate -> {
            boolean created = saved == null;
            if (created) saved = candidate;
            return Uni.createFrom().item(new WriteResult(created, saved));
        };
        final ReceiveStandardScanningTrialResultService service = new ReceiveStandardScanningTrialResultService((id, token) -> {
            calls.add("authorize"); assertEquals(TOKEN, token); assertEquals(SESSION, id); return authorize.apply(id);
        }, this);
        public Uni<Optional<Stored>> find(UUID sessionId, String trialId) {
            calls.add("find"); assertEquals(SESSION, sessionId); assertEquals("synthetic-trial", trialId); return read.apply(trialId);
        }
        public Uni<WriteResult> insertIfAbsent(Stored candidate) { calls.add("insert"); return write.apply(candidate); }
        ReceiveStandardScanningTrialResultService.Status receive(StandardScanningTrialResult result) {
            return service.receive(result, TOKEN).await().atMost(Duration.ofSeconds(1));
        }
    }

    @Test void authorizesThenValidatesAndStoresTheResultWithTheServerSuppliedContext() {
        var f = new Fixture(); var input = result(1200);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.CREATED, f.receive(input));
        assertEquals(List.of("authorize", "find", "insert"), f.calls);
        assertEquals(new Stored(CONTEXT, input), f.saved);
    }

    @Test void identicalReplaysAreReadOnlyEvenAfterClinicalClosesTheSession() {
        for (boolean mayInsert : List.of(true, false)) {
            var f = new Fixture(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
            f.permission = new Decision(Permission.PERMITTED, CONTEXT, mayInsert);
            assertEquals(ReceiveStandardScanningTrialResultService.Status.ALREADY_STORED, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find"), f.calls); assertSame(before, f.saved);
        }
    }

    @Test void closedSessionCannotCreateOrOverwriteAResult() {
        var f = new Fixture(); f.permission = new Decision(Permission.PERMITTED, CONTEXT, false);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.DENIED, f.receive(result(1200)));
        assertNull(f.saved); assertEquals(List.of("authorize", "find"), f.calls);
        f.calls.clear(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
        assertEquals(ReceiveStandardScanningTrialResultService.Status.CONFLICT, f.receive(result(1300)));
        assertSame(before, f.saved); assertEquals(List.of("authorize", "find"), f.calls);
    }

    @Test void deniedInvalidAndUnavailableClinicalResponsesNeverReadStoredResults() {
        for (var permission : List.of(Permission.DENIED, Permission.INVALID_TOKEN, Permission.UNAVAILABLE)) {
            var f = new Fixture(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
            f.permission = new Decision(permission, null, false);
            var expected = switch (permission) {
                case DENIED -> ReceiveStandardScanningTrialResultService.Status.DENIED;
                case INVALID_TOKEN -> ReceiveStandardScanningTrialResultService.Status.INVALID_TOKEN;
                default -> ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE;
            };
            assertEquals(expected, f.receive(result(1200))); assertEquals(List.of("authorize"), f.calls); assertSame(before, f.saved);
        }
        for (boolean throwsError : List.of(true, false)) {
            var f = new Fixture(); f.authorize = id -> { if (throwsError) throw new RuntimeException("private authorization failure"); return null; };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
            assertEquals(List.of("authorize"), f.calls);
        }
    }

    @Test void reusesTheCoreValidatorAndRejectsCrossSessionAuthorizationFacts() {
        var f = new Fixture();
        assertEquals(ReceiveStandardScanningTrialResultService.Status.INVALID_RESULT, f.receive(result(3001)));
        assertEquals(List.of("authorize"), f.calls); assertNull(f.saved);
        f.calls.clear(); f.permission = new Decision(Permission.PERMITTED,
                new StandardScanningTrialContext(new UUID(0, 9), CONFIG, 3, 500, 3000), true);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
        assertEquals(List.of("authorize"), f.calls); assertNull(f.saved);
    }

    @Test void missingCredentialsOrResultNeverCallPorts() {
        var f = new Fixture();
        assertEquals(ReceiveStandardScanningTrialResultService.Status.INVALID_TOKEN,
                f.service.receive(result(1200), " ").await().atMost(Duration.ofSeconds(1)));
        assertEquals(ReceiveStandardScanningTrialResultService.Status.INVALID_TOKEN,
                f.service.receive(result(1200), null).await().atMost(Duration.ofSeconds(1)));
        assertEquals(ReceiveStandardScanningTrialResultService.Status.INVALID_RESULT, f.receive(null));
        assertTrue(f.calls.isEmpty());
    }

    @Test void readFailuresAndMalformedStoredBindingsFailClosedWithoutWriting() {
        for (int kind = 0; kind < 5; kind++) {
            var f = new Fixture(); int k = kind;
            f.read = id -> switch (k) {
                case 0 -> { throw new RuntimeException("private read failure"); }
                case 1 -> Uni.createFrom().failure(new RuntimeException("private read failure"));
                case 2 -> null;
                case 3 -> Uni.createFrom().nullItem();
                default -> Uni.createFrom().item(Optional.of(new Stored(
                        new StandardScanningTrialContext(SESSION, CONFIG, 4, 500, 3000), result(1200))));
            };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find"), f.calls); assertNull(f.saved);
        }
    }

    @Test void writeFailuresAreUnknownAndNeverAutomaticallyRetried() {
        for (int kind = 0; kind < 4; kind++) {
            var f = new Fixture(); int k = kind;
            f.write = candidate -> switch (k) {
                case 0 -> { throw new RuntimeException("private write failure"); }
                case 1 -> Uni.createFrom().failure(new RuntimeException("private write failure"));
                case 2 -> null;
                default -> Uni.createFrom().nullItem();
            };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find", "insert"), f.calls);
        }
    }

    @Test void lostAcknowledgementCanBeConfirmedAfterClosureUsingTheSameIdentityAndContent() {
        var f = new Fixture();
        f.write = candidate -> { f.saved = candidate; return Uni.createFrom().failure(new RuntimeException("lost acknowledgement")); };
        assertEquals(ReceiveStandardScanningTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
        var durable = f.saved; f.calls.clear(); f.permission = new Decision(Permission.PERMITTED, CONTEXT, false);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.ALREADY_STORED, f.receive(result(1200)));
        assertSame(durable, f.saved); assertEquals(List.of("authorize", "find"), f.calls);
    }

    @Test void insertRaceUsesTheAcknowledgedWinnerWithoutReplacingIt() {
        for (long winnerDuration : List.of(1200L, 1300L)) {
            var f = new Fixture();
            f.write = candidate -> { f.saved = new Stored(CONTEXT, result(winnerDuration)); return Uni.createFrom().item(new WriteResult(false, f.saved)); };
            assertEquals(winnerDuration == 1200 ? ReceiveStandardScanningTrialResultService.Status.ALREADY_STORED
                    : ReceiveStandardScanningTrialResultService.Status.CONFLICT, f.receive(result(1200)));
            assertEquals(result(winnerDuration), f.saved.result()); assertEquals(List.of("authorize", "find", "insert"), f.calls);
        }
    }

    @Test void inconsistentWriteAcknowledgementIsNotReportedAsSuccessful() {
        var f = new Fixture();
        f.write = candidate -> Uni.createFrom().item(new WriteResult(true, new Stored(CONTEXT, result(1300))));
        assertEquals(ReceiveStandardScanningTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
        assertEquals(List.of("authorize", "find", "insert"), f.calls);
    }

    @Test void eachSubscriptionObtainsFreshAuthorizationWithoutCachedPermission() {
        var f = new Fixture(); var pending = f.service.receive(result(1200), TOKEN);
        assertTrue(f.calls.isEmpty());
        assertEquals(ReceiveStandardScanningTrialResultService.Status.CREATED, pending.await().atMost(Duration.ofSeconds(1)));
        f.calls.clear(); f.permission = new Decision(Permission.DENIED, null, false);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.DENIED, pending.await().atMost(Duration.ofSeconds(1)));
        assertEquals(List.of("authorize"), f.calls);
    }

    @Test void portValuesRejectIncoherentFactsAndKeepDiagnosticsRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new Decision(null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.PERMITTED, null, true));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.PERMITTED, null, false));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.DENIED, CONTEXT, true));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.DENIED, CONTEXT, false));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.UNAVAILABLE, null, true));
        assertThrows(IllegalArgumentException.class, () -> new Stored(null, result(1200)));
        assertThrows(IllegalArgumentException.class, () -> new Stored(CONTEXT, null));
        assertThrows(IllegalArgumentException.class, () -> new Stored(CONTEXT, result(3001)));
        assertThrows(IllegalArgumentException.class, () -> new WriteResult(true, null));
        for (var value : List.of(new Decision(Permission.PERMITTED, CONTEXT, true), new Stored(CONTEXT, result(1200)),
                new WriteResult(true, new Stored(CONTEXT, result(1200))))) {
            String text = value.toString(); assertFalse(text.contains(SESSION.toString())); assertFalse(text.contains("synthetic-trial"));
        }
    }

    @Test void preservesAllTerminalOutcomesAndProducerProvenanceWithoutCalculatingMetrics() {
        for (var outcome : Outcome.values()) {
            var f = new Fixture();
            long duration = outcome == Outcome.TIMEOUT ? 3000 : 1200;
            Long dwell = outcome == Outcome.SUCCESS ? Long.valueOf(500) : null;
            var input = new StandardScanningTrialResult(1, SESSION, "synthetic-trial", outcome,
                    duration, dwell, DataOrigin.PRODUCER_REPORTED);
            assertEquals(ReceiveStandardScanningTrialResultService.Status.CREATED, f.receive(input));
            assertEquals(new Stored(CONTEXT, input), f.saved);
            assertEquals(List.of("authorize", "find", "insert"), f.calls);
        }
    }

    @Test void wrongStoredIdentityIsUnavailableAndWrongWinnerIsUncertainNeverSuccessful() {
        var unrelated = new Stored(CONTEXT, new StandardScanningTrialResult(1, SESSION,
                "other-synthetic-trial", Outcome.SUCCESS, 1200, 500L, DataOrigin.SYNTHETIC));
        var read = new Fixture(); read.saved = unrelated;
        assertEquals(ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE, read.receive(result(1200)));
        assertSame(unrelated, read.saved); assertEquals(List.of("authorize", "find"), read.calls);
        for (boolean created : List.of(true, false)) {
            var write = new Fixture();
            write.write = candidate -> { write.saved = unrelated; return Uni.createFrom().item(new WriteResult(created, unrelated)); };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, write.receive(result(1200)));
            assertSame(unrelated, write.saved); assertEquals(List.of("authorize", "find", "insert"), write.calls);
        }
    }
    @Test void closedReplayStillRequiresFreshClinicalEvenAfterLostSynchronousAcknowledgement() {
        for (boolean nullDecision : List.of(true, false)) {
            var f = new Fixture();
            f.write = candidate -> { f.saved = candidate; throw new RuntimeException("lost synthetic acknowledgement"); };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
            var before = f.saved; f.calls.clear();
            f.authorize = id -> { if (nullDecision) return null; throw new RuntimeException("synthetic unavailable"); };
            assertEquals(ReceiveStandardScanningTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
            assertEquals(List.of("authorize"), f.calls); assertSame(before, f.saved);
            f.calls.clear(); f.authorize = id -> new Decision(Permission.PERMITTED, CONTEXT, false);
            assertEquals(ReceiveStandardScanningTrialResultService.Status.ALREADY_STORED, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find"), f.calls); assertSame(before, f.saved);
        }
    }
    @Test void invalidFrozenPolicyCannotReadOrChangeExistingSavedResult() {
        var f = new Fixture(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
        f.permission = new Decision(Permission.PERMITTED, CONTEXT, false);
        assertEquals(ReceiveStandardScanningTrialResultService.Status.INVALID_RESULT, f.receive(result(3001)));
        assertEquals(List.of("authorize"), f.calls); assertSame(before, f.saved);
    }
}
