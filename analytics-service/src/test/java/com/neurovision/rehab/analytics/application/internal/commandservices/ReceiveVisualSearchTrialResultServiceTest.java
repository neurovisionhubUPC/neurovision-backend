package com.neurovision.rehab.analytics.application.internal.commandservices;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort.Decision;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialAuthorizationPort.Permission;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.Stored;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.WriteResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult.*;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReceiveVisualSearchTrialResultServiceTest {
    private static final UUID SESSION = new UUID(0, 1), CONFIG = new UUID(0, 2);
    private static final String TOKEN = "synthetic-original-token";
    private static final VisualSearchTrialContext CONTEXT = new VisualSearchTrialContext(SESSION, CONFIG, 3, 500, 3000);
    private static VisualSearchTrialResult result(long duration) {
        return new VisualSearchTrialResult(1, SESSION, "synthetic-trial", Outcome.SUCCESS, duration, 500L, DataOrigin.SYNTHETIC);
    }
    private static final class Fixture implements VisualSearchTrialResultStore {
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
        final ReceiveVisualSearchTrialResultService service = new ReceiveVisualSearchTrialResultService((id, token) -> {
            calls.add("authorize"); assertEquals(TOKEN, token); assertEquals(SESSION, id); return authorize.apply(id);
        }, this);
        public Uni<Optional<Stored>> find(UUID sessionId, String trialId) {
            calls.add("find"); assertEquals(SESSION, sessionId); assertEquals("synthetic-trial", trialId); return read.apply(trialId);
        }
        public Uni<WriteResult> insertIfAbsent(Stored candidate) { calls.add("insert"); return write.apply(candidate); }
        ReceiveVisualSearchTrialResultService.Status receive(VisualSearchTrialResult result) {
            return service.receive(result, TOKEN).await().atMost(Duration.ofSeconds(1));
        }
    }

    @Test void authorizesThenValidatesAndStoresTheResultWithTheServerSuppliedContext() {
        var f = new Fixture(); var input = result(1200);
        assertEquals(ReceiveVisualSearchTrialResultService.Status.CREATED, f.receive(input));
        assertEquals(List.of("authorize", "find", "insert"), f.calls);
        assertEquals(new Stored(CONTEXT, input), f.saved);
    }

    @Test void identicalReplaysAreReadOnlyEvenAfterClinicalClosesTheSession() {
        for (boolean mayInsert : List.of(true, false)) {
            var f = new Fixture(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
            f.permission = new Decision(Permission.PERMITTED, CONTEXT, mayInsert);
            assertEquals(ReceiveVisualSearchTrialResultService.Status.ALREADY_STORED, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find"), f.calls); assertSame(before, f.saved);
        }
    }

    @Test void closedSessionCannotCreateOrOverwriteAResult() {
        var f = new Fixture(); f.permission = new Decision(Permission.PERMITTED, CONTEXT, false);
        assertEquals(ReceiveVisualSearchTrialResultService.Status.DENIED, f.receive(result(1200)));
        assertNull(f.saved); assertEquals(List.of("authorize", "find"), f.calls);
        f.calls.clear(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
        assertEquals(ReceiveVisualSearchTrialResultService.Status.CONFLICT, f.receive(result(1300)));
        assertSame(before, f.saved); assertEquals(List.of("authorize", "find"), f.calls);
    }

    @Test void deniedInvalidAndUnavailableClinicalResponsesNeverReadStoredResults() {
        for (var permission : List.of(Permission.DENIED, Permission.INVALID_TOKEN, Permission.UNAVAILABLE)) {
            var f = new Fixture(); f.saved = new Stored(CONTEXT, result(1200)); var before = f.saved;
            f.permission = new Decision(permission, null, false);
            var expected = switch (permission) {
                case DENIED -> ReceiveVisualSearchTrialResultService.Status.DENIED;
                case INVALID_TOKEN -> ReceiveVisualSearchTrialResultService.Status.INVALID_TOKEN;
                default -> ReceiveVisualSearchTrialResultService.Status.UNAVAILABLE;
            };
            assertEquals(expected, f.receive(result(1200))); assertEquals(List.of("authorize"), f.calls); assertSame(before, f.saved);
        }
        for (boolean throwsError : List.of(true, false)) {
            var f = new Fixture(); f.authorize = id -> { if (throwsError) throw new RuntimeException("private authorization failure"); return null; };
            assertEquals(ReceiveVisualSearchTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
            assertEquals(List.of("authorize"), f.calls);
        }
    }

    @Test void reusesTheCoreValidatorAndRejectsCrossSessionAuthorizationFacts() {
        var f = new Fixture();
        assertEquals(ReceiveVisualSearchTrialResultService.Status.INVALID_RESULT, f.receive(result(3001)));
        assertEquals(List.of("authorize"), f.calls); assertNull(f.saved);
        f.calls.clear(); f.permission = new Decision(Permission.PERMITTED,
                new VisualSearchTrialContext(new UUID(0, 9), CONFIG, 3, 500, 3000), true);
        assertEquals(ReceiveVisualSearchTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
        assertEquals(List.of("authorize"), f.calls); assertNull(f.saved);
    }

    @Test void missingCredentialsOrResultNeverCallPorts() {
        var f = new Fixture();
        assertEquals(ReceiveVisualSearchTrialResultService.Status.INVALID_TOKEN,
                f.service.receive(result(1200), " ").await().atMost(Duration.ofSeconds(1)));
        assertEquals(ReceiveVisualSearchTrialResultService.Status.INVALID_TOKEN,
                f.service.receive(result(1200), null).await().atMost(Duration.ofSeconds(1)));
        assertEquals(ReceiveVisualSearchTrialResultService.Status.INVALID_RESULT, f.receive(null));
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
                        new VisualSearchTrialContext(SESSION, CONFIG, 4, 500, 3000), result(1200))));
            };
            assertEquals(ReceiveVisualSearchTrialResultService.Status.UNAVAILABLE, f.receive(result(1200)));
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
            assertEquals(ReceiveVisualSearchTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
            assertEquals(List.of("authorize", "find", "insert"), f.calls);
        }
    }

    @Test void lostAcknowledgementCanBeConfirmedAfterClosureUsingTheSameIdentityAndContent() {
        var f = new Fixture();
        f.write = candidate -> { f.saved = candidate; return Uni.createFrom().failure(new RuntimeException("lost acknowledgement")); };
        assertEquals(ReceiveVisualSearchTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
        var durable = f.saved; f.calls.clear(); f.permission = new Decision(Permission.PERMITTED, CONTEXT, false);
        assertEquals(ReceiveVisualSearchTrialResultService.Status.ALREADY_STORED, f.receive(result(1200)));
        assertSame(durable, f.saved); assertEquals(List.of("authorize", "find"), f.calls);
    }

    @Test void insertRaceUsesTheAcknowledgedWinnerWithoutReplacingIt() {
        for (long winnerDuration : List.of(1200L, 1300L)) {
            var f = new Fixture();
            f.write = candidate -> { f.saved = new Stored(CONTEXT, result(winnerDuration)); return Uni.createFrom().item(new WriteResult(false, f.saved)); };
            assertEquals(winnerDuration == 1200 ? ReceiveVisualSearchTrialResultService.Status.ALREADY_STORED
                    : ReceiveVisualSearchTrialResultService.Status.CONFLICT, f.receive(result(1200)));
            assertEquals(result(winnerDuration), f.saved.result()); assertEquals(List.of("authorize", "find", "insert"), f.calls);
        }
    }

    @Test void inconsistentWriteAcknowledgementIsNotReportedAsSuccessful() {
        var f = new Fixture();
        f.write = candidate -> Uni.createFrom().item(new WriteResult(true, new Stored(CONTEXT, result(1300))));
        assertEquals(ReceiveVisualSearchTrialResultService.Status.WRITE_OUTCOME_UNKNOWN, f.receive(result(1200)));
        assertEquals(List.of("authorize", "find", "insert"), f.calls);
    }

    @Test void eachSubscriptionObtainsFreshAuthorizationWithoutCachedPermission() {
        var f = new Fixture(); var pending = f.service.receive(result(1200), TOKEN);
        assertTrue(f.calls.isEmpty());
        assertEquals(ReceiveVisualSearchTrialResultService.Status.CREATED, pending.await().atMost(Duration.ofSeconds(1)));
        f.calls.clear(); f.permission = new Decision(Permission.DENIED, null, false);
        assertEquals(ReceiveVisualSearchTrialResultService.Status.DENIED, pending.await().atMost(Duration.ofSeconds(1)));
        assertEquals(List.of("authorize"), f.calls);
    }

    @Test void portValuesRejectIncoherentFactsAndKeepDiagnosticsRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.PERMITTED, null, true));
        assertThrows(IllegalArgumentException.class, () -> new Decision(Permission.DENIED, CONTEXT, true));
        assertThrows(IllegalArgumentException.class, () -> new Stored(CONTEXT, result(3001)));
        assertThrows(IllegalArgumentException.class, () -> new WriteResult(true, null));
        for (var value : List.of(new Decision(Permission.PERMITTED, CONTEXT, true), new Stored(CONTEXT, result(1200)),
                new WriteResult(true, new Stored(CONTEXT, result(1200))))) {
            String text = value.toString(); assertFalse(text.contains(SESSION.toString())); assertFalse(text.contains("synthetic-trial"));
        }
    }
}
