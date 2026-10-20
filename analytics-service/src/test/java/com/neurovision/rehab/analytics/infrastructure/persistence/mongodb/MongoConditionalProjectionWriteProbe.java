package com.neurovision.rehab.analytics.infrastructure.persistence.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClients;
import com.neurovision.rehab.analytics.application.ports.out.persistence.ConditionalProjectionRebuildWritePort.*;
import com.neurovision.rehab.analytics.application.ports.out.persistence.TelemetryInboxPort;
import com.neurovision.rehab.analytics.application.internal.commandservices.ApplyBaselineProjectionRebuildService;
import com.neurovision.rehab.analytics.application.internal.queryservices.PreviewBaselineProjectionRebuildService;
import com.neurovision.rehab.analytics.domain.model.valueobjects.*;
import com.neurovision.rehab.analytics.domain.services.BaselineTelemetryMetricsCalculator;
import io.quarkus.mongodb.impl.ReactiveMongoClientImpl;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.bson.BsonDocument;
import org.reactivestreams.Publisher;

/** Standalone synthetic fixture probe, never an application bean or automatically executed test. */
public final class MongoConditionalProjectionWriteProbe {
    private static final Duration CALL = Duration.ofSeconds(2);
    private static final UUID SESSION = new UUID(0, 101), STREAM = new UUID(0, 102);
    private static final String URI = "mongodb://mongo:27017/?retryWrites=false&connectTimeoutMS=2000"
            + "&serverSelectionTimeoutMS=2000&socketTimeoutMS=2000&appName=conditional-write-probe";
    private MongoConditionalProjectionWriteProbe() { }

    public static void main(String[] args) {
        // The future ownership-checked launcher must suppress driver logs and retain only protocol lines.
        boolean rebuild = args != null && args.length > 0 && "--isolated-synthetic-rebuild".equals(args[0]);
        try {
            Report result = rebuild ? executeRebuild(args, LiveFactory::new) : execute(args, LiveFactory::new);
            System.out.println(rebuild ? result.safeRebuildLine() : result.safeLine());
        } catch (ProbeFailure failure) {
            System.out.println(rebuild ? failure.safeRebuildLine() : failure.safeLine());
            if (failure.safeDiagnosticLine() != null) System.out.println(failure.safeDiagnosticLine());
            throw failure; // Nonzero natural exit; never force a successful JVM exit.
        }
    }

    static Report executeRebuild(String[] args, Function<String, RebuildFactory> create) {
        if (args == null || args.length != 2 || !"--isolated-synthetic-rebuild".equals(args[0])
                || args[1] == null || !args[1].matches("[0-9a-f]{32}")) throw new ProbeFailure("admission");
        var progress = new Progress();
        try (RebuildFactory factory = create.apply(args[1])) {
            try {
                factory.observe(progress);
                for (var scenario : RebuildScenario.values()) {
                    progress.at(null, Stage.CASE_OPEN);
                    var store = factory.openRebuild("rebuild_" + scenario.name().toLowerCase(Locale.ROOT));
                    boolean stale = scenario == RebuildScenario.STALE;
                    progress.at(null, Stage.SEED_INBOX);
                    store.seedInbox(stale ? 2 : 1, scenario == RebuildScenario.INVALID_SOURCE);
                    if (stale) store.seed(rebuildCandidate(1), true);
                    var evidence = store.sentinels();
                    var before = store.rows();
                    check(store.mutations() == 0 && store.rebuildFinds() == 0 && store.rebuildIndexes() == 0);
                    progress.at(null, Stage.WRITE);
                    var result = store.apply(scenario != RebuildScenario.DENIED);
                    switch (scenario) {
                        case MISSING, STALE -> {
                            check(result.status() == ApplyBaselineProjectionRebuildService.Status.APPLIED);
                            exact(store, rebuildCandidate(stale ? 2 : 1), stale);
                            check(store.mutations() == 1 && store.rebuildFinds() == 4 && store.rebuildIndexes() == 1);
                            check(store.apply(true).status() == ApplyBaselineProjectionRebuildService.Status.NO_CHANGE);
                            exact(store, rebuildCandidate(stale ? 2 : 1), stale);
                            check(store.mutations() == 1 && store.rebuildFinds() == 8 && store.rebuildIndexes() == 1);
                        }
                        case DENIED -> {
                            check(result.status() == ApplyBaselineProjectionRebuildService.Status.NOT_AUTHORIZED);
                            check(store.rebuildFinds() == 0 && store.rebuildIndexes() == 0 && store.mutations() == 0);
                            check(before.equals(store.rows()));
                        }
                        case INVALID_SOURCE -> {
                            check(result.status() == ApplyBaselineProjectionRebuildService.Status.REFUSED);
                            check(result.observation() != null && result.observation().status() == PreviewBaselineProjectionRebuildService.Status.SOURCE_INVALID);
                            check(store.rebuildFinds() == 2 && store.rebuildIndexes() == 0 && store.mutations() == 0);
                            check(before.equals(store.rows()));
                        }
                    }
                    progress.at(null, Stage.VERIFY);
                    check(evidence.equals(store.sentinels()));
                }
                progress.at(null, Stage.FACTORY_CLOSE);
                return new Report(RebuildScenario.values().length);
            } catch (ProbeFailure failure) { throw failure; }
            catch (Exception failure) { throw progress.unavailable(failure); }
        } catch (ProbeFailure failure) { throw failure; }
        catch (Exception failure) { throw progress.unavailable(failure); }
    }

    static StreamSummaryProjection rebuildCandidate(int batches) { return projection(batches, .2); }
    static Document rebuildSource(int sequence, boolean invalid) {
        var time = Instant.parse("2026-09-18T10:00:00Z").plusSeconds(sequence);
        var batch = new AcceptedTelemetryBatch(1, new UUID(1, sequence), new UUID(2, sequence), SESSION, STREAM,
                sequence, time, time.plusMillis(1), time.plusMillis(2), new UUID(3, sequence), null,
                List.of(new AcceptedTelemetryBatch.GazeSample(0, .2, .3, .9)), List.of(), "synthetic-hash");
        var document = MongoTelemetryInboxAdapter.admittedDocument(batch, TelemetryAdmissionQuality.IN_ORDER,
                new TelemetryInboxPort.Provenance("synthetic-topic", 0, sequence, 128), time);
        if (invalid) document.put("status", "fixture-invalid");
        return document;
    }
    enum RebuildScenario { MISSING, STALE, DENIED, INVALID_SOURCE }
    interface RebuildFactory extends Factory { RebuildStore openRebuild(String name); }
    interface RebuildStore extends Store {
        void seedInbox(int batches, boolean invalid);
        ApplyBaselineProjectionRebuildService.Outcome apply(boolean permitted);
        int rebuildFinds();
        int rebuildIndexes();
    }

    static Report execute(String[] args, Function<String, Factory> create) {
        if (args == null || args.length != 2 || !"--isolated-synthetic-write".equals(args[0])
                || args[1] == null || !args[1].matches("[0-9a-f]{32}")) throw new ProbeFailure("admission");
        var progress = new Progress();
        try (Factory factory = create.apply(args[1])) {
            try {
                factory.observe(progress);
                var report = matrix(factory, progress);
                progress.at(null, Stage.FACTORY_CLOSE);
                return report;
            } catch (ProbeFailure failure) {
                throw failure;
            } catch (Exception failure) {
                // Snapshot the primary failure before try-with-resources starts closing the factory.
                throw progress.unavailable(failure);
            }
        } catch (ProbeFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw progress.unavailable(failure);
        }
    }

    static Report matrix(Factory factory) {
        var progress = new Progress();
        factory.observe(progress);
        return matrix(factory, progress);
    }

    private static Report matrix(Factory factory, Progress progress) {
        progress.at(null, Stage.FIXTURE_BUILD);
        var a = projection(1, .2);
        var b = projection(2, .3);
        var c = projection(3, .4);
        var expected = new AnalyticsSummaryProjection(a.identity(), 1, a.summary());
        int passed = 0;
        for (var scenario : Scenario.values()) {
            progress.at(scenario, Stage.CASE_OPEN);
            Index policy = scenario == Scenario.MISSING_INDEX ? Index.MISSING
                    : scenario == Scenario.NON_UNIQUE_INDEX ? Index.NON_UNIQUE : Index.UNIQUE;
            Store store = factory.open(scenario.name().toLowerCase(Locale.ROOT), policy);
            if (scenario != Scenario.INSERT && scenario != Scenario.INSERT_RACE
                    && policy == Index.UNIQUE) {
                progress.at(scenario, Stage.SEED_PROJECTION);
                store.seed(scenario == Scenario.REVISION_CONFLICT ? b
                        : scenario == Scenario.CONTENT_CONFLICT ? projection(1, .7) : a, true);
            }
            var sentinels = progress.step(Stage.READ_SENTINELS, store::sentinels);
            var before = progress.step(Stage.READ_PROJECTION, store::rows);
            progress.at(scenario, Stage.VERIFY);
            check(store.mutations() == 0);
            progress.at(scenario, Stage.WRITE);
            switch (scenario) {
                case INSERT -> {
                    check(write(store, 0, new WriteRequest(a, null)) == WriteResult.INSERTED);
                    exact(store, a, false);
                }
                case REPEAT_INSERT -> {
                    check(write(store, 0, new WriteRequest(a, null)) == WriteResult.PRECONDITION_FAILED);
                    check(before.equals(store.rows()));
                }
                case REPLACE -> {
                    check(write(store, 0, new WriteRequest(b, expected)) == WriteResult.REPLACED);
                    exact(store, b, true);
                }
                case REVISION_CONFLICT, CONTENT_CONFLICT -> {
                    check(write(store, 0, new WriteRequest(c, expected)) == WriteResult.PRECONDITION_FAILED);
                    check(before.equals(store.rows()));
                }
                case INSERT_RACE, REPLACE_RACE -> {
                    progress.at(scenario, Stage.RACE);
                    boolean insert = scenario == Scenario.INSERT_RACE;
                    var left = new WriteRequest(b, insert ? null : expected);
                    var right = new WriteRequest(c, insert ? null : expected);
                    var results = race(store, left, right);
                    WriteResult won = insert ? WriteResult.INSERTED : WriteResult.REPLACED;
                    check(results.stream().filter(r -> r == won).count() == 1);
                    check(results.stream().filter(r -> r == WriteResult.PRECONDITION_FAILED).count() == 1);
                    exact(store, results.getFirst() == won ? b : c, !insert);
                }
                case MISSING_INDEX, NON_UNIQUE_INDEX -> {
                    boolean failed = false;
                    try { store.write(0, new WriteRequest(a, null)); }
                    catch (ProbeFailure contractFailure) { throw contractFailure; }
                    catch (RuntimeException rejected) {
                        if (!"Conditional projection write unavailable".equals(rejected.getMessage())) {
                            throw new ProbeFailure("write_outcome_unknown");
                        }
                        failed = true;
                    }
                    check(failed);
                    check(store.metadataComplete());
                    check(before.equals(store.rows()));
                }
            }
            progress.at(scenario, Stage.VERIFY);
            int writes = policy != Index.UNIQUE ? 0
                    : scenario == Scenario.INSERT_RACE || scenario == Scenario.REPLACE_RACE ? 2 : 1;
            check(store.mutations() == writes);
            check(sentinels.equals(progress.step(Stage.READ_SENTINELS, store::sentinels)));
            passed++;
        }
        return new Report(passed);
    }

    private static WriteResult write(Store store, int lane, WriteRequest request) {
        try { return store.write(lane, request); }
        catch (RuntimeException unknown) { throw new ProbeFailure("write_outcome_unknown"); }
    }

    private static List<WriteResult> race(Store store, WriteRequest left, WriteRequest right) {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var jobs = new ArrayList<Future<WriteResult>>();
        try {
            for (int lane = 0; lane < 2; lane++) {
                final int selected = lane;
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) throw new ProbeFailure("coordination");
                    return write(store, selected, selected == 0 ? left : right);
                }));
            }
            if (!ready.await(2, TimeUnit.SECONDS)) throw new ProbeFailure("coordination");
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            var outcomes = new ArrayList<WriteResult>();
            for (var job : jobs) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ProbeFailure("write_outcome_unknown");
                outcomes.add(job.get(remaining, TimeUnit.NANOSECONDS));
            }
            return outcomes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ProbeFailure("write_outcome_unknown");
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new ProbeFailure("write_outcome_unknown");
        } finally {
            start.countDown();
            for (var job : jobs) if (!job.isDone()) job.cancel(true);
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) throw new ProbeFailure("shutdown");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new ProbeFailure("shutdown");
            }
        }
    }

    private static void exact(Store store, StreamSummaryProjection winner, boolean extra) {
        var wanted = document(winner);
        if (extra) wanted.append("fixtureExtra", "preserve");
        check(List.of(wanted).equals(store.rows()));
    }

    static Document document(StreamSummaryProjection projection) {
        return new Document("therapySessionId", projection.identity().therapySessionId().toString())
                .append("streamId", projection.identity().streamId().toString())
                .append("calculationVersion", projection.identity().calculationVersion())
                .append("admittedBatchCount", projection.admittedBatchCount())
                .append("summary", MongoStreamSummaryProjectionAdapter.summaryDocument(projection.summary()));
    }

    private static StreamSummaryProjection projection(long revision, double x) {
        var time = Instant.parse("2026-09-18T10:00:00Z");
        var rows = new ArrayList<AdmittedTelemetryBatch>();
        for (int i = 0; i < revision; i++) {
            var batch = new AcceptedTelemetryBatch(1, new UUID(1, i), new UUID(2, i), SESSION, STREAM,
                    i, time.plusSeconds(i), time.plusSeconds(i).plusMillis(1), time.plusSeconds(i).plusMillis(2),
                    new UUID(3, i), null, List.of(new AcceptedTelemetryBatch.GazeSample(0, x, .3, .9)),
                    List.of(), "synthetic-hash");
            rows.add(new AdmittedTelemetryBatch(batch, TelemetryAdmissionQuality.IN_ORDER));
        }
        return new StreamSummaryProjection(new AnalyticsProjectionIdentity(SESSION, STREAM, "baseline-v1"),
                revision, new BaselineTelemetryMetricsCalculator().calculate(rows));
    }

    private static void check(boolean condition) { if (!condition) throw new ProbeFailure("assertion"); }
    enum Scenario { INSERT, REPEAT_INSERT, REPLACE, REVISION_CONFLICT, CONTENT_CONFLICT,
        INSERT_RACE, REPLACE_RACE, MISSING_INDEX, NON_UNIQUE_INDEX }
    enum Index { UNIQUE, MISSING, NON_UNIQUE }
    interface Factory extends AutoCloseable {
        default void observe(Progress progress) { }
        Store open(String name, Index policy);
        @Override void close();
    }
    interface Store {
        void seed(StreamSummaryProjection projection, boolean extra);
        List<Document> rows();
        List<Document> sentinels();
        WriteResult write(int lane, WriteRequest request);
        int mutations();
        default boolean metadataComplete() { return false; }
    }
    record Report(int passed) {
        String safeLine() { return "conditional_write_probe status=pass cases=" + passed; }
        String safeRebuildLine() { return "baseline_rebuild_probe status=pass cases=" + passed; }
    }
    static final class ProbeFailure extends RuntimeException {
        private final String code;
        private final Diagnostic diagnostic;
        ProbeFailure(String code) { this(code, null); }
        private ProbeFailure(String code, Diagnostic diagnostic) {
            super("Conditional write probe failed", null, false, false);
            this.code = code;
            this.diagnostic = diagnostic;
        }
        String code() { return code; }
        String safeLine() { return "conditional_write_probe status=fail reason=" + code; }
        String safeRebuildLine() { return "baseline_rebuild_probe status=fail reason=" + code; }
        String safeDiagnosticLine() { return diagnostic == null ? null : diagnostic.safeLine(); }
    }

    enum Stage { FACTORY_OPEN, FIXTURE_BUILD, CASE_OPEN, CREATE_COLLECTION, CREATE_INBOX, CREATE_INDEX,
        SEED_SENTINELS, SEED_INBOX, SEED_PROJECTION, READ_SENTINELS, READ_PROJECTION,
        READ_SENTINEL_PROJECTIONS, READ_INBOX, WRITE, RACE, VERIFY, FACTORY_CLOSE }
    enum Family { MONGO_TIMEOUT, MONGO_COMMAND, MONGO_OTHER, AWAIT_TIMEOUT, CODEC,
        INVALID_ARGUMENT, ILLEGAL_STATE, REJECTED_EXECUTION, OTHER }
    private record Diagnostic(Scenario scenario, Stage stage, Family family) {
        String safeLine() {
            return "NV_CAS_DIAGNOSTIC case=" + (scenario == null ? "none" : scenario.name().toLowerCase(Locale.ROOT))
                    + " stage=" + stage.name().toLowerCase(Locale.ROOT)
                    + " family=" + family.name().toLowerCase(Locale.ROOT);
        }
    }
    /** Parent-thread progress only; contender tasks do not mutate this context. */
    static final class Progress {
        private Scenario scenario;
        private Stage stage = Stage.FACTORY_OPEN;
        void at(Scenario scenario, Stage stage) { this.scenario = scenario; this.stage = stage; }
        <T> T step(Stage stage, Supplier<T> operation) { this.stage = stage; return operation.get(); }
        ProbeFailure unavailable(Exception failure) {
            return new ProbeFailure("probe_unavailable", new Diagnostic(scenario, stage, family(failure)));
        }
        private static Family family(Exception failure) {
            // Top-level types only: no class names, messages, codes, causes or stack inspection.
            if (failure instanceof com.mongodb.MongoTimeoutException
                    || failure instanceof com.mongodb.MongoExecutionTimeoutException) return Family.MONGO_TIMEOUT;
            if (failure instanceof com.mongodb.MongoCommandException) return Family.MONGO_COMMAND;
            if (failure instanceof com.mongodb.MongoException) return Family.MONGO_OTHER;
            if (failure instanceof io.smallrye.mutiny.TimeoutException) return Family.AWAIT_TIMEOUT;
            if (failure instanceof org.bson.codecs.configuration.CodecConfigurationException) return Family.CODEC;
            if (failure instanceof IllegalArgumentException) return Family.INVALID_ARGUMENT;
            if (failure instanceof IllegalStateException) return Family.ILLEGAL_STATE;
            if (failure instanceof RejectedExecutionException) return Family.REJECTED_EXECUTION;
            return Family.OTHER;
        }
    }

    /** Used only by the explicitly admitted standalone main, not by offline JUnit tests. */
    private static final class LiveFactory implements RebuildFactory {
        private final List<ReactiveMongoClientImpl> clients = new ArrayList<>();
        private final List<Audit> audits = List.of(new Audit(), new Audit());
        private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        private final String database;
        private Progress progress = new Progress();
        LiveFactory(String runId) {
            database = "nv_conditional_probe_" + runId;
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            Infrastructure.setDefaultExecutor(scheduler);
            try {
                for (var audit : audits) {
                    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(URI))
                            .writeConcern(WriteConcern.MAJORITY).addCommandListener(audit).build();
                    clients.add(new ReactiveMongoClientImpl(MongoClients.create(settings)));
                }
                for (var client : clients) {
                    check(read(client.unwrap().getDatabase(database).withTimeout(2000, TimeUnit.MILLISECONDS)
                            .listCollectionNames().first()).isEmpty());
                }
            } catch (RuntimeException failure) {
                close(); throw new ProbeFailure("fixture_admission");
            }
        }
        public Store open(String name, Index policy) {
            return new LiveStore(clients, audits, database, name, policy, progress);
        }
        public RebuildStore openRebuild(String name) {
            return new LiveStore(clients, audits, database, name, Index.UNIQUE, progress);
        }
        public void observe(Progress progress) { this.progress = progress; }
        public void close() {
            boolean closed = true;
            for (var client : clients) {
                try { client.close(); } catch (RuntimeException failure) { closed = false; }
            }
            scheduler.shutdownNow();
            try { closed &= scheduler.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); closed = false; }
            if (!closed) throw new ProbeFailure("shutdown");
        }
    }

    private static final class LiveStore implements RebuildStore {
        private final com.mongodb.reactivestreams.client.MongoCollection<Document> collection, inbox;
        private final List<MongoConditionalProjectionRebuildWriteAdapter> adapters;
        private final List<Audit> audits;
        private final String database, name;
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicInteger rebuildFinds = new AtomicInteger(), rebuildIndexes = new AtomicInteger();
        private final ReactiveMongoClient rebuildClient;
        private volatile boolean metadataComplete;
        private final Progress progress;
        private final Document identity = new Document("therapySessionId", SESSION.toString())
                .append("streamId", STREAM.toString()).append("calculationVersion", "baseline-v1");
        LiveStore(List<ReactiveMongoClientImpl> clients, List<Audit> audits, String database, String name,
                Index policy, Progress progress) {
            this.progress = progress;
            this.rebuildClient = clients.getFirst();
            this.audits = audits; this.database = database; this.name = name;
            var db = clients.getFirst().unwrap().getDatabase(database).withTimeout(2000, TimeUnit.MILLISECONDS);
            // createCollection is deliberately non-idempotent: never reuse an existing fixture collection.
            progress.step(Stage.CREATE_COLLECTION, () -> read(db.createCollection(name)));
            progress.step(Stage.CREATE_INBOX, () -> read(db.createCollection("inbox_" + name)));
            collection = db.getCollection(name);
            inbox = db.getCollection("inbox_" + name);
            if (policy != Index.MISSING) {
                progress.step(Stage.CREATE_INDEX, () -> read(collection.createIndex(new Document("therapySessionId", 1).append("streamId", 1)
                        .append("calculationVersion", 1), new IndexOptions()
                        .name("uq_analytics_stream_calculation").unique(policy == Index.UNIQUE))));
            }
            progress.step(Stage.SEED_SENTINELS, () -> read(collection.insertMany(List.of(
                    document(projection(1, .2)).append("streamId", new UUID(0, 999).toString()),
                    document(projection(1, .2)).append("calculationVersion", "fixture-other-version")))));
            progress.step(Stage.SEED_INBOX, () -> read(inbox.insertOne(new Document("fixtureSentinel", "unchanged"))));
            adapters = clients.stream().map(client -> new MongoConditionalProjectionRebuildWriteAdapter(
                    instance(client), database, name)).toList();
        }
        public void seed(StreamSummaryProjection projection, boolean extra) {
            var value = document(projection);
            if (extra) value.append("fixtureExtra", "preserve");
            progress.step(Stage.SEED_PROJECTION, () -> read(collection.insertOne(value)));
        }
        public List<Document> rows() { return progress.step(Stage.READ_PROJECTION, () -> bounded(collection.find(identity))); }
        public List<Document> sentinels() {
            var result = new ArrayList<>(progress.step(Stage.READ_SENTINEL_PROJECTIONS,
                    () -> bounded(collection.find(new Document("$nor", List.of(identity))))));
            result.addAll(progress.step(Stage.READ_INBOX, () -> bounded(inbox.find())));
            return List.copyOf(result);
        }
        public WriteResult write(int lane, WriteRequest request) {
            var audit = audits.get(lane);
            var window = audit.begin(database, name);
            try {
                return adapters.get(lane).writeIfUnchanged(request, CALL).await().atMost(CALL.plusMillis(500));
            } finally {
                audit.finish(window);
                metadataComplete = window.metadataComplete();
                mutations.addAndGet(window.writes.get());
                if (window.invalid) throw new ProbeFailure("command_contract");
            }
        }
        public int mutations() { return mutations.get(); }
        public boolean metadataComplete() { return metadataComplete; }
        public void seedInbox(int batches, boolean invalid) {
            var documents = new ArrayList<Document>();
            for (int i = 0; i < batches; i++) {
                documents.add(rebuildSource(i, invalid));
            }
            progress.step(Stage.SEED_INBOX, () -> read(inbox.insertMany(documents)));
        }
        public ApplyBaselineProjectionRebuildService.Outcome apply(boolean permitted) {
            var audit = audits.getFirst();
            var window = audit.beginRebuild(database, name, "inbox_" + name);
            try {
                return MongoBaselineProjectionRebuildFactory.create(instance(rebuildClient), database, "inbox_" + name, name)
                        .apply(SESSION, STREAM, "baseline-v1", permitted).await().atMost(Duration.ofMillis(5500));
            } finally {
                audit.finish(window);
                mutations.addAndGet(window.writes.get());
                rebuildFinds.addAndGet(window.finds.get());
                rebuildIndexes.addAndGet(window.indexes.get());
                if (window.invalid) throw new ProbeFailure("command_contract");
            }
        }
        public int rebuildFinds() { return rebuildFinds.get(); }
        public int rebuildIndexes() { return rebuildIndexes.get(); }
        private static List<Document> bounded(com.mongodb.reactivestreams.client.FindPublisher<Document> find) {
            var result = read(find.projection(new Document("_id", 0)).sort(new Document("_id", 1)).limit(5));
            check(result.size() < 5);
            return result;
        }
    }

    /** Holds counters only. Fixture setup/readback occurs outside these per-client capture windows. */
    static final class Audit implements CommandListener {
        private final AtomicReference<Window> active = new AtomicReference<>();
        Window begin(String database, String collection) {
            var window = new Window(database, collection);
            if (!active.compareAndSet(null, window)) throw new ProbeFailure("coordination");
            return window;
        }
        Window beginRebuild(String database, String collection, String inbox) {
            var window = new Window(database, collection, inbox);
            if (!active.compareAndSet(null, window)) throw new ProbeFailure("coordination");
            return window;
        }
        void finish(Window window) {
            if (!active.compareAndSet(window, null)) throw new ProbeFailure("coordination");
        }
        @Override public void commandStarted(CommandStartedEvent event) {
            record(event.getDatabaseName(), event.getCommandName(), event.getCommand());
        }
        @Override public void commandSucceeded(CommandSucceededEvent event) { succeeded(event.getCommandName()); }
        @Override public void commandFailed(CommandFailedEvent event) { failed(event.getCommandName()); }
        void succeeded(String commandName) {
            var window = active.get();
            if (window != null && metadata(commandName)) window.metadataSucceeded.incrementAndGet();
        }
        void failed(String commandName) {
            var window = active.get();
            if (window != null && metadata(commandName)) window.metadataFailed = true;
        }
        private static boolean metadata(String commandName) {
            return commandName.equals("listIndexes") || commandName.equals("getMore");
        }
        void record(String database, String commandName, BsonDocument command) {
            var window = active.get();
            if (window == null) return;
            // Topology monitoring and session management can use admin, never the fixture database.
            if (database.equals("admin") && Set.of("hello", "isMaster", "endSessions").contains(commandName)) return;
            if (!database.equals(window.database)) { window.invalid = true; return; }
            if (window.inbox != null && commandName.equals("find")) {
                var target = command.get("find");
                boolean source = target != null && target.isString() && window.inbox.equals(target.asString().getValue());
                var expected = rebuildFind(source ? window.inbox : window.collection, source);
                try {
                    for (String field : List.of("find", "filter", "projection")) {
                        if (!Objects.equals(expected.get(field), command.get(field))) window.invalid = true;
                    }
                    for (String field : List.of("limit", "batchSize")) {
                        if (command.getNumber(field).longValue() != expected.getNumber(field).longValue()) window.invalid = true;
                    }
                    long max = command.getNumber("maxTimeMS").longValue();
                    if (max < 1 || max > 2000 || !Objects.equals(expected.get("sort"), command.get("sort"))) window.invalid = true;
                } catch (RuntimeException invalid) { window.invalid = true; }
                window.finds.incrementAndGet();
                return;
            }
            String key = switch (commandName) {
                case "listIndexes", "insert", "update" -> commandName;
                case "getMore", "killCursors" -> commandName.equals("getMore") ? "collection" : "killCursors";
                default -> null;
            };
            var target = key == null ? null : command.get(key);
            boolean inboxCursor = window.inbox != null && Set.of("getMore", "killCursors").contains(commandName)
                    && target != null && target.isString() && window.inbox.equals(target.asString().getValue());
            if (!inboxCursor && (target == null || !target.isString() || !window.collection.equals(target.asString().getValue()))) {
                window.invalid = true; return;
            }
            if (commandName.equals("listIndexes")) window.indexes.incrementAndGet();
            if (metadata(commandName)) window.metadataStarted.incrementAndGet();
            if (commandName.equals("insert") || commandName.equals("update")) {
                if (window.writes.incrementAndGet() > 1) window.invalid = true;
            }
        }
    }
    static final class Window {
        final String database, collection, inbox;
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger finds = new AtomicInteger(), indexes = new AtomicInteger();
        final AtomicInteger metadataStarted = new AtomicInteger(), metadataSucceeded = new AtomicInteger();
        volatile boolean invalid, metadataFailed;
        Window(String database, String collection) { this(database, collection, null); }
        Window(String database, String collection, String inbox) { this.database = database; this.collection = collection; this.inbox = inbox; }
        boolean metadataComplete() {
            return !invalid && !metadataFailed && metadataStarted.get() > 0
                    && metadataStarted.get() == metadataSucceeded.get();
        }
    }

    /** Expected fixed synthetic read contract; never contains runtime patient or source content. */
    static BsonDocument rebuildFind(String collection, boolean source) {
        var terms = new ArrayList<Document>(List.of(new Document("therapySessionId", SESSION.toString()), new Document("streamId", STREAM.toString())));
        if (!source) terms.add(new Document("calculationVersion", "baseline-v1"));
        var fields = new Document("_id", 0);
        String names = source ? "schemaVersion attemptEventId batchId therapySessionId streamId sequence capturedAt sentAt serverReceivedAt correlationId traceId gazeSamples gameplaySamples payloadSha256 admissionQuality status inboxSchemaVersion"
                : "therapySessionId streamId calculationVersion admittedBatchCount summary";
        for (String field : names.split(" ")) fields.append(field, 1);
        var command = new Document("find", collection).append("filter", new Document("$and", terms))
                // Pinned driver 5.6.5 serializes requested batchSize=limit=2 as wire batchSize=3.
                // The strict projection document limit remains 2; source 65/8 is unchanged.
                .append("projection", fields).append("limit", source ? 65L : 2L).append("batchSize", source ? 8 : 3).append("maxTimeMS", 2000L);
        if (source) command.append("sort", new Document("sequence", 1).append("batchId", 1));
        return command.toBsonDocument();
    }

    @SuppressWarnings("unchecked")
    private static Instance<ReactiveMongoClient> instance(ReactiveMongoClient client) {
        return (Instance<ReactiveMongoClient>) Proxy.newProxyInstance(Instance.class.getClassLoader(),
                new Class<?>[]{Instance.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) return client;
                    throw new IllegalStateException("Unsupported probe client access");
                });
    }
    private static <T> List<T> read(Publisher<T> publisher) {
        return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher)).collect().asList()
                .await().atMost(CALL.plusMillis(500));
    }
}
