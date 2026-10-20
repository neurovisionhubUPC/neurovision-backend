package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort.*;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MongoConditionalProjectionWriteProbeTest {
    @Test void rebuildAuditUsesPinnedDriverWireBatchWithoutIncreasingProjectionLimit() {
        var audit = new MongoConditionalProjectionWriteProbe.Audit();
        // MongoDB driver 5.6.5 FindOperation increments batchSize when it equals a positive limit.
        // This fixture represents the serialized command, not the adapter's requested FindOptions.
        var wire = MongoConditionalProjectionWriteProbe.rebuildFind("summaries", false);
        wire.put("limit", new org.bson.BsonInt32(2));
        wire.put("batchSize", new org.bson.BsonInt32(3));
        var window = audit.beginRebuild("fixture", "summaries", "inbox");
        audit.record("fixture", "find", wire); audit.finish(window);
        assertFalse(window.invalid);
        assertEquals(2, MongoConditionalProjectionWriteProbe.rebuildFind("summaries", false).getNumber("limit").intValue());
        assertEquals(3, MongoConditionalProjectionWriteProbe.rebuildFind("summaries", false).getNumber("batchSize").intValue());
        for (int[] bounds : List.of(new int[]{2, 2}, new int[]{2, 4}, new int[]{2, 0},
                new int[]{1, 3}, new int[]{3, 3}, new int[]{0, 3})) {
            var rejected = wire.clone();
            rejected.put("limit", new org.bson.BsonInt32(bounds[0]));
            rejected.put("batchSize", new org.bson.BsonInt32(bounds[1]));
            window = audit.beginRebuild("fixture", "summaries", "inbox");
            audit.record("fixture", "find", rejected); audit.finish(window);
            assertTrue(window.invalid);
        }
        for (long timeout : List.of(0L, 2001L)) {
            var rejected = wire.clone(); rejected.put("maxTimeMS", new org.bson.BsonInt64(timeout));
            window = audit.beginRebuild("fixture", "summaries", "inbox");
            audit.record("fixture", "find", rejected); audit.finish(window); assertTrue(window.invalid);
        }
        var source = MongoConditionalProjectionWriteProbe.rebuildFind("inbox", true);
        assertEquals(65, source.getNumber("limit").intValue());
        assertEquals(8, source.getNumber("batchSize").intValue());
        window = audit.beginRebuild("fixture", "summaries", "inbox");
        audit.record("fixture", "find", source); audit.finish(window); assertFalse(window.invalid);
        window = audit.begin("fixture", "summaries");
        audit.record("fixture", "find", wire); audit.finish(window); assertTrue(window.invalid);
    }

    @Test void actualRebuildSourceDocumentsReconstructTheExpectedCandidateWithoutMongo() {
        for (int count : List.of(1, 2)) {
            var rows = new ArrayList<AdmittedTelemetryBatch>();
            for (int i = 0; i < count; i++) {
                var source = MongoConditionalProjectionWriteProbe.rebuildSource(i, false);
                assertEquals("ADMITTED", source.getString("status"));
                assertEquals(2, ((Number) source.get("inboxSchemaVersion")).intValue());
                rows.add(MongoTelemetryInboxAdapter.reconstruct(source));
            }
            assertEquals(MongoConditionalProjectionWriteProbe.rebuildCandidate(count).summary(),
                    new com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator().calculate(rows));
        }
        assertEquals("fixture-invalid", MongoConditionalProjectionWriteProbe.rebuildSource(0, true).getString("status"));
        assertEquals("baseline_rebuild_probe status=fail reason=admission",
                new MongoConditionalProjectionWriteProbe.ProbeFailure("admission").safeRebuildLine());
    }

    @Test void rebuildModeHasSeparateAdmissionAndFourCasesWithoutChangingOldProtocol() {
        var calls = new AtomicInteger();
        for (String[] args : List.of(new String[]{}, validArgs(),
                new String[]{"--isolated-synthetic-rebuild", "private"})) {
            assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.executeRebuild(args, id -> { calls.incrementAndGet(); return null; }));
        }
        assertEquals(0, calls.get());
        var factory = new FakeRebuildFactory("ok");
        var report = MongoConditionalProjectionWriteProbe.executeRebuild(rebuildArgs(), id -> factory);
        assertEquals("baseline_rebuild_probe status=pass cases=4", report.safeRebuildLine());
        assertEquals(4, factory.stores.size());
        assertEquals(List.of(true, true, true, true, false, true), factory.permissions);
        assertTrue(factory.closed);
        assertEquals(9, MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> new FakeFactory()).passed());
    }

    @Test void rebuildCannotPassWithWrongOutcomeContentCountersOrChangedEvidence() {
        for (String mode : List.of("wrong_status", "wrong_content", "extra_write", "denied_read", "inbox_change", "close_fail")) {
            var factory = new FakeRebuildFactory(mode);
            assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.executeRebuild(rebuildArgs(), id -> factory));
            assertTrue(factory.closed);
        }
    }

    @Test void rebuildCaptureAdmitsOnlyExactBoundedFindsAndNeverInboxWrites() {
        var audit = new MongoConditionalProjectionWriteProbe.Audit();
        var window = audit.beginRebuild("fixture", "summaries", "inbox");
        audit.record("fixture", "find", MongoConditionalProjectionWriteProbe.rebuildFind("inbox", true));
        audit.record("fixture", "find", MongoConditionalProjectionWriteProbe.rebuildFind("summaries", false));
        audit.finish(window);
        assertFalse(window.invalid); assertEquals(2, window.finds.get());
        for (String mode : List.of("inbox_write", "other_collection", "unbounded", "identity", "projection", "batch", "ddl")) {
            window = audit.beginRebuild("fixture", "summaries", "inbox");
            var command = MongoConditionalProjectionWriteProbe.rebuildFind("inbox", true);
            String operation = "find";
            switch (mode) {
                case "inbox_write" -> { operation = "insert"; command = org.bson.BsonDocument.parse("{insert:'inbox'}"); }
                case "other_collection" -> command.put("find", new org.bson.BsonString("other"));
                case "unbounded" -> command.put("limit", new org.bson.BsonInt64(0));
                case "identity" -> command.put("filter", new org.bson.BsonDocument());
                case "projection" -> command.remove("projection");
                case "batch" -> command.put("batchSize", new org.bson.BsonInt32(100));
                case "ddl" -> { operation = "createIndexes"; command = org.bson.BsonDocument.parse("{createIndexes:'summaries'}"); }
            }
            audit.record("fixture", operation, command); audit.finish(window); assertTrue(window.invalid);
        }
        window = audit.begin("fixture", "summaries");
        audit.record("fixture", "find", MongoConditionalProjectionWriteProbe.rebuildFind("summaries", false));
        audit.finish(window); assertTrue(window.invalid);
    }

    private static String[] rebuildArgs() { return new String[]{"--isolated-synthetic-rebuild", "a".repeat(32)}; }

    private static final class FakeRebuildFactory implements MongoConditionalProjectionWriteProbe.RebuildFactory {
        final String mode;
        final List<MongoConditionalProjectionWriteProbe.RebuildStore> stores = new ArrayList<>();
        final List<Boolean> permissions = new ArrayList<>();
        boolean closed;
        FakeRebuildFactory(String mode) { this.mode = mode; }
        public MongoConditionalProjectionWriteProbe.Store open(String name, MongoConditionalProjectionWriteProbe.Index index) {
            throw new AssertionError("Raw mode was not requested");
        }
        public MongoConditionalProjectionWriteProbe.RebuildStore openRebuild(String name) {
            var store = new FakeRebuildStore(this); stores.add(store); return store;
        }
        public void close() { closed = true; if (mode.equals("close_fail")) throw new IllegalStateException("private"); }
    }

    private static final class FakeRebuildStore implements MongoConditionalProjectionWriteProbe.RebuildStore {
        final FakeRebuildFactory factory;
        final FakeStore storage = new FakeStore(true, "ok");
        int batches, finds, indexes;
        boolean invalid, applied;
        String inbox = "unchanged";
        FakeRebuildStore(FakeRebuildFactory factory) { this.factory = factory; }
        public void seedInbox(int batches, boolean invalid) { this.batches = batches; this.invalid = invalid; }
        public void seed(StreamSummaryProjection projection, boolean extra) { storage.seed(projection, extra); }
        public List<Document> rows() { return storage.rows(); }
        public List<Document> sentinels() { return List.of(new Document("inbox", inbox)); }
        public WriteResult write(int lane, WriteRequest request) { throw new AssertionError("Not the raw path"); }
        public int mutations() { return storage.mutations(); }
        public int rebuildFinds() { return finds; }
        public int rebuildIndexes() { return indexes; }
        public com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Outcome apply(boolean permitted) {
            factory.permissions.add(permitted);
            var status = com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Status.NOT_AUTHORIZED;
            com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService.Outcome observation = null;
            if (!permitted) { if (factory.mode.equals("denied_read")) finds++; }
            else if (invalid) {
                finds += 2; status = com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Status.REFUSED;
                observation = new com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService.Outcome(
                        com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService.Status.SOURCE_INVALID, null, null, null);
            } else {
                finds += 4;
                status = applied ? com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Status.NO_CHANGE
                        : com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Status.APPLIED;
                if (!applied) {
                    var expected = storage.rows().isEmpty() ? null : MongoConditionalProjectionWriteProbe.rebuildCandidate(1);
                    var prior = expected == null ? null : new AnalyticsSummaryProjection(expected.identity(), 1, expected.summary());
                    storage.write(0, new WriteRequest(MongoConditionalProjectionWriteProbe.rebuildCandidate(batches), prior)); indexes++; applied = true;
                }
            }
            if (factory.mode.equals("wrong_status")) status = com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Status.CONFLICT;
            if (factory.mode.equals("wrong_content") && !storage.documents.isEmpty()) storage.documents.getFirst().put("admittedBatchCount", 999L);
            if (factory.mode.equals("extra_write")) storage.mutations++;
            if (factory.mode.equals("inbox_change")) inbox = "changed";
            return new com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService.Outcome(status, observation);
        }
    }

    @Test void admissionDoesNotRunAnythingWithoutExactExplicitSyntheticInputs() {
        var calls = new AtomicInteger();
        for (String[] args : List.of(new String[]{}, new String[]{"--isolated-synthetic-write"},
                new String[]{"--isolated-synthetic-write", "mongodb://private"},
                new String[]{"--other", "a".repeat(32)},
                new String[]{"--isolated-synthetic-write", "a".repeat(32), "extra"})) {
            var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(args, id -> {
                        calls.incrementAndGet(); return new FakeFactory();
                    }));
            assertEquals("admission", failure.code());
        }
        assertEquals(0, calls.get());
    }

    @Test void completeOfflineMatrixRequiresOneWinnerAndExactPersistedContent() {
        var factory = new FakeFactory();
        var report = MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory);
        assertEquals(9, report.passed());
        assertTrue(factory.closed);
        assertEquals(9, factory.stores.size());
        assertEquals(11, factory.stores.stream().mapToInt(s -> s.calls).sum());
        assertEquals("conditional_write_probe status=pass cases=9", report.safeLine());
    }

    @Test void aLostAcknowledgementStopsTheMatrixWithoutRetryOrFalseNoEffect() {
        var factory = new FakeFactory(); factory.mode = "lost_ack";
        var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("write_outcome_unknown", failure.code());
        assertNull(failure.safeDiagnosticLine());
        assertEquals(1, factory.stores.size());
        assertEquals(1, factory.stores.getFirst().calls);
        assertEquals(1, factory.stores.getFirst().documents.size());
        assertTrue(factory.closed);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private"));
    }

    @Test void incorrectContentFalseSuccessAndCleanupFailureCannotPass() {
        for (String mode : List.of("wrong_content", "both_win", "close_fail", "sentinel_change")) {
            var factory = new FakeFactory(); factory.mode = mode;
            assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
            assertTrue(factory.closed);
        }
    }

    @Test void protocolNeverPrintsUnknownCategoriesOrRawExceptions() {
        var factory = new FakeFactory(); factory.mode = "setup_fail";
        var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("probe_unavailable", failure.code());
        assertEquals("conditional_write_probe status=fail reason=probe_unavailable", failure.safeLine());
        assertEquals(0, failure.getStackTrace().length);
        assertNull(failure.getCause());
    }

    @Test void genericFailureAddsOnlyFixedCaseStageAndFamilyWithoutChangingTheVerdict() {
        var factory = new FakeFactory(); factory.mode = "setup_fail";
        var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("conditional_write_probe status=fail reason=probe_unavailable", failure.safeLine());
        assertEquals("NV_CAS_DIAGNOSTIC case=insert stage=case_open family=illegal_state", failure.safeDiagnosticLine());
        assertEquals(0, failure.getStackTrace().length);
        assertNull(failure.getCause());
        assertTrue(factory.closed);
    }

    @Test void primaryDiagnosticIsNotOverwrittenByASecondaryCloseFailure() {
        var factory = new FakeFactory(); factory.mode = "setup_and_close_fail";
        var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("NV_CAS_DIAGNOSTIC case=insert stage=case_open family=illegal_state", failure.safeDiagnosticLine());
        assertEquals(0, failure.getSuppressed().length);
        assertNull(failure.getCause());
    }

    @Test void globalOpenAndCloseFailuresDoNotBorrowTheLastCase() {
        var opening = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> { throw new IllegalArgumentException("private"); }));
        assertEquals("NV_CAS_DIAGNOSTIC case=none stage=factory_open family=invalid_argument", opening.safeDiagnosticLine());
        var factory = new FakeFactory(); factory.mode = "close_fail";
        var closing = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("NV_CAS_DIAGNOSTIC case=none stage=factory_close family=illegal_state", closing.safeDiagnosticLine());
    }

    @Test void typedOperationStagesClassifyOnlyTheOuterExceptionAndNeverInspectMessages() {
        var exceptions = List.of(new com.mongodb.MongoTimeoutException("private"),
                new com.mongodb.MongoCommandException(new org.bson.BsonDocument("code", new org.bson.BsonInt32(123))
                        .append("errmsg", new org.bson.BsonString("private")), new com.mongodb.ServerAddress("127.0.0.1", 27017)),
                new com.mongodb.MongoException("private"), new io.smallrye.mutiny.TimeoutException(),
                new org.bson.codecs.configuration.CodecConfigurationException("private"),
                new IllegalArgumentException("private"), new IllegalStateException("private"),
                new java.util.concurrent.RejectedExecutionException("private"),
                new RuntimeException("private") { @Override public String getMessage() { throw new AssertionError("Do not inspect messages"); } });
        var families = List.of("mongo_timeout", "mongo_command", "mongo_other", "await_timeout", "codec",
                "invalid_argument", "illegal_state", "rejected_execution", "other");
        for (int i = 0; i < exceptions.size(); i++) {
            final var exception = exceptions.get(i);
            final var stage = MongoConditionalProjectionWriteProbe.Stage.CREATE_INDEX;
            var factory = new FakeFactory(); factory.failure = exception; factory.failureStage = stage;
            var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
            assertEquals("NV_CAS_DIAGNOSTIC case=insert stage=create_index family=" + families.get(i), failure.safeDiagnosticLine());
            assertFalse(failure.safeDiagnosticLine().contains("private"));
            assertNull(failure.getCause());
        }
    }

    @Test void everyStageRendersOnlyItsFixedTokenAndExplicitFailuresRemainUnchanged() {
        for (var stage : MongoConditionalProjectionWriteProbe.Stage.values()) {
            var factory = new FakeFactory(); factory.failureStage = stage; factory.failure = new RuntimeException("private");
            var failure = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
            assertEquals("NV_CAS_DIAGNOSTIC case=insert stage=" + stage.name().toLowerCase(Locale.ROOT)
                    + " family=other", failure.safeDiagnosticLine());
        }
        var factory = new FakeFactory(); factory.mode = "wrong_content";
        var assertion = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        assertEquals("assertion", assertion.code());
        assertNull(assertion.safeDiagnosticLine());
        var opening = assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> {
                    throw new MongoConditionalProjectionWriteProbe.ProbeFailure("fixture_admission");
                }));
        assertEquals("fixture_admission", opening.code());
        assertNull(opening.safeDiagnosticLine());
    }

    @Test void commandCaptureExcludesSetupAndRejectsWrongNamespacesDdlAndDuplicateDispatch() {
        var audit = new MongoConditionalProjectionWriteProbe.Audit();
        audit.record("fixture", "createIndexes", org.bson.BsonDocument.parse("{}"));
        var window = audit.begin("fixture", "summaries");
        audit.record("fixture", "listIndexes", org.bson.BsonDocument.parse("{listIndexes:'summaries'}"));
        audit.record("fixture", "insert", org.bson.BsonDocument.parse("{insert:'summaries'}"));
        audit.finish(window);
        assertEquals(1, window.writes.get());
        assertFalse(window.invalid);
        for (String command : List.of("createIndexes", "drop", "delete")) {
            window = audit.begin("fixture", "summaries");
            audit.record("fixture", command, new org.bson.BsonDocument());
            audit.finish(window);
            assertTrue(window.invalid);
        }
        window = audit.begin("fixture", "summaries");
        audit.record("fixture", "update", org.bson.BsonDocument.parse("{update:'other'}"));
        audit.finish(window);
        assertTrue(window.invalid);
        window = audit.begin("fixture", "summaries");
        audit.record("fixture", "insert", org.bson.BsonDocument.parse("{insert:'summaries'}"));
        audit.record("fixture", "insert", org.bson.BsonDocument.parse("{insert:'summaries'}"));
        audit.finish(window);
        assertTrue(window.invalid);
    }

    @Test void noPassWhenGuardsDispatchWritesOrAnOperationDispatchesTwice() {
        for (String mode : List.of("guard_dispatch", "double_dispatch", "guard_contract")) {
            var factory = new FakeFactory(); factory.mode = mode;
            assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
        }
    }

    @Test void negativeIndexCasesRequireSuccessfulMetadataAndNoMetadataFailure() {
        for (String mode : List.of("metadata_never_succeeded", "metadata_failed")) {
            var factory = new FakeFactory(); factory.mode = mode;
            assertThrows(MongoConditionalProjectionWriteProbe.ProbeFailure.class,
                    () -> MongoConditionalProjectionWriteProbe.execute(validArgs(), id -> factory));
            assertEquals(1, factory.stores.get(7).calls);
            assertEquals(0, factory.stores.get(7).mutations);
        }
        var audit = new MongoConditionalProjectionWriteProbe.Audit();
        var window = audit.begin("fixture", "summaries");
        audit.record("fixture", "listIndexes", org.bson.BsonDocument.parse("{listIndexes:'summaries'}"));
        assertFalse(window.metadataComplete());
        audit.succeeded("listIndexes");
        assertTrue(window.metadataComplete());
        audit.failed("getMore");
        assertFalse(window.metadataComplete());
        audit.finish(window);
    }

    private static String[] validArgs() { return new String[]{"--isolated-synthetic-write", "a".repeat(32)}; }

    private static final class FakeFactory implements MongoConditionalProjectionWriteProbe.Factory {
        final List<FakeStore> stores = new ArrayList<>();
        String mode = "ok";
        boolean closed;
        MongoConditionalProjectionWriteProbe.Progress progress;
        MongoConditionalProjectionWriteProbe.Stage failureStage;
        RuntimeException failure;
        public void observe(MongoConditionalProjectionWriteProbe.Progress progress) { this.progress = progress; }
        public MongoConditionalProjectionWriteProbe.Store open(String name, MongoConditionalProjectionWriteProbe.Index policy) {
            if (failure != null) return progress.step(failureStage, () -> { throw failure; });
            if (mode.equals("setup_fail") || mode.equals("setup_and_close_fail")) throw new IllegalStateException("private");
            var store = new FakeStore(policy == MongoConditionalProjectionWriteProbe.Index.UNIQUE, mode); stores.add(store); return store;
        }
        public void close() { closed = true; if (mode.equals("close_fail") || mode.equals("setup_and_close_fail")) throw new IllegalStateException("private"); }
    }

    private static final class FakeStore implements MongoConditionalProjectionWriteProbe.Store {
        final List<Document> documents = new ArrayList<>();
        final boolean safeIndex;
        final String mode;
        int calls, mutations;
        FakeStore(boolean safeIndex, String mode) { this.safeIndex = safeIndex; this.mode = mode; }
        public synchronized void seed(StreamSummaryProjection projection, boolean extra) {
            var doc = MongoConditionalProjectionWriteProbe.document(projection);
            if (extra) doc.append("fixtureExtra", "preserve");
            documents.add(doc);
        }
        public synchronized List<Document> rows() {
            return documents.stream().map(d -> new org.bson.RawBsonDocument(d, new org.bson.codecs.DocumentCodec())
                    .decode(new org.bson.codecs.DocumentCodec())).toList();
        }
        public synchronized List<Document> sentinels() {
            return List.of(new Document("sentinel", mode.equals("sentinel_change") && calls > 0 ? "changed" : "unchanged"));
        }
        public synchronized WriteResult write(int lane, WriteRequest request) {
            calls++;
            if (!safeIndex) {
                if (mode.equals("guard_contract")) throw new MongoConditionalProjectionWriteProbe.ProbeFailure("command_contract");
                if (mode.equals("metadata_failed")) {
                    var operations = new MongoConditionalProjectionRebuildWriteAdapter.RawOperations() {
                        public com.mongodb.WriteConcern writeConcern() { return com.mongodb.WriteConcern.MAJORITY; }
                        public io.smallrye.mutiny.Multi<org.bson.RawBsonDocument> indexes(java.time.Duration timeout) {
                            return io.smallrye.mutiny.Multi.createFrom().failure(
                                    new com.mongodb.MongoTimeoutException("private metadata timeout"));
                        }
                        public io.smallrye.mutiny.Uni<Document> write(Document command, java.time.Duration timeout) {
                            mutations++; throw new AssertionError("Unexpected write");
                        }
                    };
                    return new MongoConditionalProjectionRebuildWriteAdapter(operations, "fixture", System::nanoTime)
                            .writeIfUnchanged(request, java.time.Duration.ofSeconds(2)).await()
                            .atMost(java.time.Duration.ofSeconds(3));
                }
                if (mode.equals("guard_dispatch")) mutations++;
                throw new IllegalStateException("Conditional projection write unavailable");
            }
            mutations += mode.equals("double_dispatch") ? 2 : 1;
            var next = MongoConditionalProjectionWriteProbe.document(request.candidate());
            WriteResult result;
            if (request.expected() == null) {
                if (!documents.isEmpty()) return mode.equals("both_win") ? WriteResult.INSERTED : WriteResult.PRECONDITION_FAILED;
                documents.add(next); result = WriteResult.INSERTED;
            } else {
                if (documents.isEmpty()) return WriteResult.PRECONDITION_FAILED;
                var old = documents.getFirst();
                var expected = request.expected();
                if (!Objects.equals(old.get("admittedBatchCount"), expected.revision())
                        || !Objects.equals(old.get("summary"), MongoStreamSummaryProjectionAdapter.summaryDocument(expected.summary()))) {
                    return WriteResult.PRECONDITION_FAILED;
                }
                old.put("admittedBatchCount", next.get("admittedBatchCount"));
                old.put("summary", next.get("summary")); result = WriteResult.REPLACED;
            }
            if (mode.equals("lost_ack")) throw new IllegalStateException("private lost response");
            if (mode.equals("wrong_content")) documents.getFirst().put("admittedBatchCount", 999L);
            return result;
        }
        public synchronized int mutations() { return mutations; }
        public boolean metadataComplete() {
            return safeIndex || !(mode.equals("metadata_never_succeeded") || mode.equals("metadata_failed"));
        }
    }
}
