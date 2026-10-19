package com.neurovision.rehab.analytics.infrastructure.observability;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import org.bson.codecs.configuration.CodecConfigurationException;

@ApplicationScoped
public class MicrometerAnalyticsPersistenceFailureAdapter implements AnalyticsPersistenceFailurePort {
    private final MeterRegistry meters;

    @Inject
    public MicrometerAnalyticsPersistenceFailureAdapter(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void record(Operation operation, Throwable failure) {
        Throwable cause = classifiedCause(failure);
        meters.counter("analytics.persistence.failures", "operation", operation.name().toLowerCase(Locale.ROOT),
                "family", familyOf(cause)).increment();
        if (cause instanceof MongoCommandException command) {
            meters.counter("analytics.persistence.mongo.command.failures",
                    "operation", operation.name().toLowerCase(Locale.ROOT),
                    "code_category", commandCategory(command.getErrorCode())).increment();
        }
    }

    static String family(Throwable failure) {
        return familyOf(classifiedCause(failure));
    }

    private static Throwable classifiedCause(Throwable failure) {
        // Bounded traversal, including cyclic cause chains; never inspect messages or stacks.
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof MongoTimeoutException || failure instanceof io.smallrye.mutiny.TimeoutException
                    || failure instanceof java.util.concurrent.TimeoutException
                    || failure instanceof MongoSocketException || failure instanceof MongoSecurityException
                    || failure instanceof MongoWriteException || failure instanceof MongoCommandException
                    || failure instanceof CodecConfigurationException || failure instanceof IllegalArgumentException) return failure;
        }
        return null;
    }

    private static String familyOf(Throwable failure) {
        if (failure instanceof MongoTimeoutException || failure instanceof io.smallrye.mutiny.TimeoutException
                || failure instanceof java.util.concurrent.TimeoutException) return "timeout";
        if (failure instanceof MongoSocketException || failure instanceof MongoSecurityException) return "mongo_connectivity";
        if (failure instanceof MongoWriteException write) return write.getCode() == 11000 ? "mongo_duplicate_key" : "mongo_write";
        if (failure instanceof MongoCommandException command) return command.getErrorCode() == 11000 ? "mongo_duplicate_key" : "mongo_command";
        if (failure instanceof CodecConfigurationException) return "serialization";
        if (failure instanceof IllegalArgumentException) return "validation";
        return "other";
    }

    private static String commandCategory(int code) {
        return switch (code) {
            case 11000, 11001, 12582 -> "duplicate_key";
            case 50 -> "execution_timeout";
            case 26 -> "namespace_not_found";
            default -> code > 0 ? "unknown_code" : "code_unavailable";
        };
    }
}
