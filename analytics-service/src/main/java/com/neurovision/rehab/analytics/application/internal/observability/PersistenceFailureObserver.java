package com.neurovision.rehab.analytics.application.internal.observability;

import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort;
import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort.Operation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;

@ApplicationScoped
public class PersistenceFailureObserver {
    public static final PersistenceFailureObserver NOOP = new PersistenceFailureObserver((operation, failure) -> {});
    private final AnalyticsPersistenceFailurePort port;

    @Inject
    public PersistenceFailureObserver(AnalyticsPersistenceFailurePort port) {
        this.port = port;
    }

    public <T> Uni<T> observe(Operation operation, Supplier<Uni<T>> action) {
        final Uni<T> result;
        try {
            // Preserve the existing assembly point, subscription count and thrown instance.
            result = action.get();
        } catch (RuntimeException failure) {
            record(operation, failure);
            throw failure;
        }
        return result.onFailure().invoke(failure -> record(operation, failure));
    }

    public <T> T calculate(Operation operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            record(operation, failure);
            throw failure;
        }
    }

    public void record(Operation operation, Throwable failure) {
        try {
            port.record(operation, failure);
        } catch (RuntimeException unavailable) {
            // Observability must not replace the failure, retries or delivery result.
        }
    }
}
