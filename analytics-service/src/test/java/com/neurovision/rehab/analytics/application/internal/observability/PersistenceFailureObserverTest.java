package com.neurovision.rehab.analytics.application.internal.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort.Operation;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersistenceFailureObserverTest {
    @Test
    void preservesSynchronousIdentityAndImmediateAssemblyEvenWhenObserverThrows() {
        var failure = new IllegalStateException("private diagnostic");
        var calls = new AtomicInteger();
        var observer = new PersistenceFailureObserver((operation, error) -> {
            assertEquals(Operation.INBOX_PREPARE, operation);
            assertSame(failure, error);
            calls.incrementAndGet();
            throw new IllegalStateException("observer unavailable");
        });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> observer.observe(Operation.INBOX_PREPARE, () -> { throw failure; })));
        assertEquals(1, calls.get());
    }

    @Test
    void preservesAsyncIdentityRetryCountAndDoesNotSubscribeEarly() {
        var failure = new IllegalStateException("private diagnostic");
        var starts = new AtomicInteger();
        var observations = new ArrayList<Throwable>();
        var observer = new PersistenceFailureObserver((operation, error) -> observations.add(error));
        var observed = observer.observe(Operation.INBOX_INSERT, () -> Uni.createFrom().deferred(() -> {
            starts.incrementAndGet();
            return Uni.createFrom().failure(failure);
        }));
        assertEquals(0, starts.get());
        var rejected = observed.onFailure().retry().atMost(2).subscribeAsCompletionStage().toCompletableFuture();
        assertSame(failure, assertThrows(CompletionException.class, rejected::join).getCause());
        assertEquals(3, starts.get());
        assertEquals(List.of(failure, failure, failure), observations);
        var throwing = new PersistenceFailureObserver((operation, error) -> { throw new IllegalStateException(); });
        var same = throwing.observe(Operation.INBOX_INSERT, () -> Uni.createFrom().failure(failure))
                .subscribeAsCompletionStage().toCompletableFuture();
        assertSame(failure, assertThrows(CompletionException.class, same::join).getCause());
    }

    @Test
    void keepsInterleavedOperationsIndependentAndSuccessSilent() {
        var operations = new ArrayList<Operation>();
        var observer = new PersistenceFailureObserver((operation, error) -> operations.add(operation));
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        var a = observer.observe(Operation.INBOX_FIND_BATCH, () -> Uni.createFrom().completionStage(first))
                .subscribeAsCompletionStage().toCompletableFuture();
        var b = observer.observe(Operation.PROJECTION_STORE, () -> Uni.createFrom().completionStage(second))
                .subscribeAsCompletionStage().toCompletableFuture();
        second.completeExceptionally(new IllegalStateException());
        first.completeExceptionally(new IllegalArgumentException());
        assertThrows(CompletionException.class, a::join);
        assertThrows(CompletionException.class, b::join);
        assertEquals(List.of(Operation.PROJECTION_STORE, Operation.INBOX_FIND_BATCH), operations);
        assertEquals("ok", observer.observe(Operation.INBOX_INSERT, () -> Uni.createFrom().item("ok"))
                .await().indefinitely());
        assertEquals(2, operations.size());
    }
}
