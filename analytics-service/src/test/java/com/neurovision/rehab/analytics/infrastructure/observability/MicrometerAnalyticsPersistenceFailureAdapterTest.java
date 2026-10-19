package com.neurovision.rehab.analytics.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.MongoCredential;
import com.neurovision.rehab.analytics.application.ports.out.observability.AnalyticsPersistenceFailurePort.Operation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.junit.jupiter.api.Test;

class MicrometerAnalyticsPersistenceFailureAdapterTest {
    @Test
    void recordsOnlyVerifiedFixedCommandCategoriesAlongsideUnchangedFamilies() {
        var meters = new SimpleMeterRegistry();
        var adapter = new MicrometerAnalyticsPersistenceFailureAdapter(meters);
        int[] codes = {11000, 11001, 12582, 50, 26, 42, Integer.MAX_VALUE, -1, 0};
        String[] categories = {"duplicate_key", "duplicate_key", "duplicate_key", "execution_timeout",
                "namespace_not_found", "unknown_code", "unknown_code", "code_unavailable", "code_unavailable"};
        for (int i = 0; i < codes.length; i++) {
            var failure = command(codes[i]);
            String family = codes[i] == 11000 ? "mongo_duplicate_key" : "mongo_command";
            assertEquals(family, MicrometerAnalyticsPersistenceFailureAdapter.family(failure));
            adapter.record(Operation.INBOX_PREPARE, new java.util.concurrent.CompletionException("private", failure));
        }
        Map<String, Double> expected = Map.of("duplicate_key", 3.0, "execution_timeout", 1.0,
                "namespace_not_found", 1.0, "unknown_code", 2.0, "code_unavailable", 2.0);
        expected.forEach((category, count) -> assertEquals(count,
                meters.get("analytics.persistence.mongo.command.failures")
                        .tags("operation", "inbox_prepare", "code_category", category).counter().count()));
        assertEquals(1.0, meters.get("analytics.persistence.failures").tag("family", "mongo_duplicate_key").counter().count());
        assertEquals(8.0, meters.get("analytics.persistence.failures").tag("family", "mongo_command").counter().count());
        for (var meter : meters.find("analytics.persistence.mongo.command.failures").meters()) {
            assertEquals(Set.of("operation", "code_category"), meter.getId().getTags().stream()
                    .map(tag -> tag.getKey()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(expected.containsKey(meter.getId().getTag("code_category")));
            assertFalse(meter.getId().toString().contains("private"));
        }
    }

    @Test
    void boundsCommandSeriesToFixedOperationsAndFiveCategories() {
        var meters = new SimpleMeterRegistry();
        var adapter = new MicrometerAnalyticsPersistenceFailureAdapter(meters);
        for (var operation : Operation.values()) {
            for (int code : new int[]{11000, 11001, 12582, 50, 26, 42, Integer.MAX_VALUE, -1, 0}) {
                adapter.record(operation, command(code));
            }
        }
        assertEquals(45, meters.find("analytics.persistence.mongo.command.failures").meters().size());
    }

    @Test
    void keepsTypedPrecedenceDepthBoundAndNonCommandFailuresUnchanged() {
        var meters = new SimpleMeterRegistry();
        var adapter = new MicrometerAnalyticsPersistenceFailureAdapter(meters);
        adapter.record(Operation.INBOX_PREPARE, new IllegalArgumentException("private", command(26)));
        Throwable atLimit = command(50);
        for (int i = 0; i < 7; i++) atLimit = new RuntimeException("private", atLimit);
        adapter.record(Operation.INBOX_PREPARE, atLimit);
        adapter.record(Operation.INBOX_PREPARE, new RuntimeException("private", atLimit));
        adapter.record(Operation.INBOX_PREPARE, new MongoWriteException(
                new WriteError(11000, "private", new BsonDocument()), new ServerAddress()));
        assertEquals(1, meters.find("analytics.persistence.mongo.command.failures").meters().size());
        assertEquals(1.0, meters.get("analytics.persistence.mongo.command.failures")
                .tag("code_category", "execution_timeout").counter().count());
        assertEquals(1.0, meters.get("analytics.persistence.failures").tag("family", "validation").counter().count());
        assertEquals(1.0, meters.get("analytics.persistence.failures").tag("family", "other").counter().count());
    }

    private static MongoCommandException command(int code) {
        return new MongoCommandException(new BsonDocument("code", new BsonInt32(code))
                .append("errmsg", new BsonString("private")), new ServerAddress()) {
            @Override public String getMessage() { throw new AssertionError("Must not inspect messages"); }
            @Override public String toString() { throw new AssertionError("Must not stringify commands"); }
            @Override public BsonDocument getResponse() { throw new AssertionError("Must not inspect response"); }
            @Override public String getErrorCodeName() { throw new AssertionError("Must not inspect code names"); }
        };
    }

    @Test
    void classifiesTypedFailuresWithoutExportingMessagesOrDynamicClassNames() {
        var server = new ServerAddress("localhost", 27017);
        Map<Throwable, String> cases = Map.of(
                new MongoTimeoutException("private"), "timeout",
                new io.smallrye.mutiny.TimeoutException(), "timeout",
                new java.util.concurrent.TimeoutException("private"), "timeout",
                new MongoSocketException("private", server), "mongo_connectivity",
                new MongoSecurityException(MongoCredential.createCredential("synthetic", "fixture", new char[0]), "private"), "mongo_connectivity",
                new MongoWriteException(new WriteError(11000, "private", new BsonDocument()), server), "mongo_duplicate_key",
                new MongoWriteException(new WriteError(42, "private", new BsonDocument()), server), "mongo_write",
                new MongoCommandException(new BsonDocument("code", new BsonInt32(42)).append("errmsg", new BsonString("private")), server), "mongo_command",
                new CodecConfigurationException("private"), "serialization",
                new IllegalArgumentException("private"), "validation");
        cases.forEach((failure, family) -> assertEquals(family,
                MicrometerAnalyticsPersistenceFailureAdapter.family(new java.util.concurrent.CompletionException(failure))));
        var privateFailure = new RuntimeException() {
            @Override public String getMessage() { throw new AssertionError("Must not inspect messages"); }
            @Override public String toString() { throw new AssertionError("Must not stringify failure"); }
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertEquals("other", MicrometerAnalyticsPersistenceFailureAdapter.family(privateFailure));
        var meters = new SimpleMeterRegistry();
        var adapter = new MicrometerAnalyticsPersistenceFailureAdapter(meters);
        for (var operation : Operation.values()) adapter.record(operation, privateFailure);
        assertEquals(9, meters.getMeters().size());
        for (var meter : meters.getMeters()) {
            assertEquals("analytics.persistence.failures", meter.getId().getName());
            assertEquals(Set.of("operation", "family"), meter.getId().getTags().stream()
                    .map(tag -> tag.getKey()).collect(java.util.stream.Collectors.toSet()));
            assertEquals("other", meter.getId().getTag("family"));
            assertFalse(meter.getId().toString().contains("private"));
        }
    }
}
