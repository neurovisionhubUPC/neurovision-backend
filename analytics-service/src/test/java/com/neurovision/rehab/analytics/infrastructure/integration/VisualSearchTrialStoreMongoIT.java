package com.neurovision.rehab.analytics.infrastructure.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.neurovision.rehab.analytics.application.internal.queryservices.CompensatorySaccadeTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.CompensatorySaccadeTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.CompensatorySaccadeTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.CompensatorySaccadeTrialResult;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoCompensatorySaccadeTrialResultStoreAdapter;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoCompensatorySaccadeTrialResultReaderAdapter;
import com.neurovision.rehab.analytics.application.internal.queryservices.VisualSearchTrialSummaryService;
import com.neurovision.rehab.analytics.application.internal.queryservices.StandardScanningTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.StandardScanningTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultReader;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoStandardScanningTrialResultReaderAdapter;
import com.neurovision.rehab.analytics.application.ports.out.authorization.VisualSearchTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultReader;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.Stored;
import com.neurovision.rehab.analytics.application.ports.out.persistence.VisualSearchTrialResultStore.WriteResult;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.VisualSearchTrialResult;
import com.neurovision.rehab.analytics.application.ports.out.persistence.StandardScanningTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.StandardScanningTrialResult;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoStandardScanningTrialResultStoreAdapter;
import com.neurovision.rehab.analytics.application.internal.queryservices.SustainedFixationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SustainedFixationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultReader;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoSustainedFixationTrialResultReaderAdapter;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SustainedFixationTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SustainedFixationTrialResult;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoSustainedFixationTrialResultStoreAdapter;
import com.neurovision.rehab.analytics.application.internal.queryservices.SystematicExplorationTrialSummaryService;
import com.neurovision.rehab.analytics.application.ports.out.authorization.SystematicExplorationTrialSummaryAuthorizationPort;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultReader;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoSystematicExplorationTrialResultReaderAdapter;
import com.neurovision.rehab.analytics.application.ports.out.persistence.SystematicExplorationTrialResultStore;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialContext;
import com.neurovision.rehab.analytics.domain.model.valueobjects.SystematicExplorationTrialResult;
import com.neurovision.rehab.analytics.infrastructure.persistence.mongodb.MongoSystematicExplorationTrialResultStoreAdapter;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.reactivestreams.Publisher;

/** Insert-only storage and bounded reads in an owned synthetic database. */
@QuarkusTest
@TestProfile(VisualSearchTrialStoreMongoProfile.class)
@Timeout(30)
@Execution(ExecutionMode.SAME_THREAD)
class VisualSearchTrialStoreMongoIT {
    @Inject VisualSearchTrialResultStore store;
    @Inject StandardScanningTrialResultStore scanningStore;
    @Inject StandardScanningTrialResultReader scanningReader;
    @Inject VisualSearchTrialResultReader reader;
    @Inject SustainedFixationTrialResultStore fixationStore;
    @Inject SustainedFixationTrialResultReader fixationReader;
    @Inject CompensatorySaccadeTrialResultStore saccadeStore;
    @Inject CompensatorySaccadeTrialResultReader saccadeReader;
    @Inject SystematicExplorationTrialResultStore systematicStore;
    @Inject SystematicExplorationTrialResultReader systematicReader;
    @Inject ReactiveMongoClient client;
    @ConfigProperty(name = "quarkus.mongodb.database") String database;
    @ConfigProperty(name = "analytics.visual-search.results-collection") String collection;
    @ConfigProperty(name = "analytics.standard-scanning.results-collection") String scanningCollection;
    @ConfigProperty(name = "analytics.sustained-fixation.results-collection") String fixationCollection;
    @ConfigProperty(name = "analytics.compensatory-saccade.results-collection") String saccadeCollection;
    @ConfigProperty(name = "analytics.systematic-exploration.results-collection") String systematicCollection;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test void insertsAndReadsAllThreeOutcomesExactly() {
        UUID session = UUID.randomUUID();
        for (var outcome : VisualSearchTrialResult.Outcome.values()) {
            var candidate = candidate(session, outcome.name(), outcome);
            assertTrue(store.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = store.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created());
            assertTrue(candidate.equals(write.stored()), "Inserted value differs");
            assertTrue(store.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Round-trip value differs");
            assertEquals(1L, count(candidate));
        }
        assertEquals(3L, await(rows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
    }

    @Test void identicalReplayReturnsOneUnchangedDocument() {
        var candidate = candidate(UUID.randomUUID(), "opaque/試行 : 1", VisualSearchTrialResult.Outcome.SUCCESS);
        assertTrue(store.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = document(candidate);
        var replay = store.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created());
        assertTrue(candidate.equals(replay.stored()), "Replay winner differs");
        assertUnchanged(candidate, before);
    }

    @Test void differentResultReturnsExistingWinnerWithoutOverwrite() {
        UUID session = UUID.randomUUID();
        var first = candidate(session, "conflict", VisualSearchTrialResult.Outcome.SUCCESS);
        var second = candidate(session, "conflict", VisualSearchTrialResult.Outcome.TIMEOUT);
        assertTrue(store.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = document(first);
        var conflict = store.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created());
        assertTrue(first.equals(conflict.stored()), "Conflict did not return existing winner");
        assertUnchanged(first, before);
    }

    @Test void concurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = candidate(session, "race", VisualSearchTrialResult.Outcome.SUCCESS);
            var second = equal ? first : candidate(session, "race", VisualSearchTrialResult.Outcome.TIMEOUT);
            var start = new CyclicBarrier(2);
            var workers = Executors.newFixedThreadPool(2);
            try {
                Future<WriteResult> left = workers.submit(() -> insertTogether(start, first));
                Future<WriteResult> right = workers.submit(() -> insertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS);
                var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one insert must create the record");
                var winner = a.created() ? a.stored() : b.stored();
                var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Acknowledged winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Race returned different winners");
                assertTrue(store.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, count(first));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned workers did not stop");
            }
        }
    }

    @Test void readerFiltersExactSessionAndOriginWithoutChangingEvidence() {
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var fixtures = new ArrayList<Stored>();
        for (var origin : VisualSearchTrialResult.DataOrigin.values()) {
            for (UUID scope : List.of(session, otherSession)) {
                var value = withOrigin(candidate(scope, "origin-" + origin,
                        VisualSearchTrialResult.Outcome.SUCCESS), origin);
                fixtures.add(value);
            }
        }
        seed(fixtures.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        for (var value : fixtures) {
            var actual = reader.read(value.result().therapySessionId(), value.result().dataOrigin(), 1001)
                    .await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(value).equals(actual), "Reader returned another session or origin");
        }
        assertTrue(before.equals(evidenceSnapshot()), "Filtering changed stored evidence");
    }

    @Test void readerReturnsAllOutcomesExactlyAndEmptyEvidenceWithoutChangingDocuments() {
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<Stored>();
        for (var outcome : VisualSearchTrialResult.Outcome.values())
            expected.add(candidate(session, "opaque/試行 : " + outcome, outcome));
        seed(expected.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        var actual = reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT);
        assertEquals(3, actual.size());
        assertTrue(new HashSet<>(expected).equals(new HashSet<>(actual)), "Reader round-trip values differ");
        assertThrows(UnsupportedOperationException.class, () -> actual.add(expected.getFirst()));
        assertTrue(reader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty origin returned evidence");
        assertTrue(reader.read(UUID.randomUUID(), VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty session returned evidence");
        assertTrue(before.equals(evidenceSnapshot()), "Reading changed stored evidence");
    }

    @Test void readerAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<Stored>();
        for (int i = 0; i < 1000; i++) expected.add(candidate(session, "bounded-" + i,
                VisualSearchTrialResult.Outcome.SUCCESS));
        seed(expected.stream().map(this::fixtureDocument).toList());
        var before = evidenceSnapshot();
        var bounded = reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 2).await().atMost(WAIT);
        assertEquals(2, bounded.size()); assertEquals(2, new HashSet<>(bounded).size());
        assertTrue(expected.containsAll(bounded), "Small bounded read returned foreign evidence");
        assertEquals(1000, reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var service = summaryService(session);
        var result = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.AVAILABLE, result.status());
        assertEquals(1000, result.summary().reportedTrialCount()); assertEquals(1000, result.summary().hitCount());
        assertEquals(0, result.summary().omissionCount()); assertEquals(0, result.summary().interruptedCount());
        assertEquals(1000, result.summary().eligibleSearchCount());
        assertEquals(0, result.summary().medianSearchDurationMs().orElseThrow().compareTo(java.math.BigDecimal.valueOf(1000)));
        assertTrue(before.equals(evidenceSnapshot()), "Bounded reading changed stored evidence");

        // An explicit fixture write, not a side effect of the reader. Keep the prior observation separate.
        seed(List.of(fixtureDocument(candidate(session, "overflow-sentinel", VisualSearchTrialResult.Outcome.SUCCESS))));
        var overflowBefore = evidenceSnapshot();
        assertEquals(1001, reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(evidenceSnapshot()), "Overflow reading changed stored evidence");
    }

    @Test void corruptFixtureIsUnavailableNotEmptyOrPartiallyReturnedAndRemainsUnchanged() {
        UUID session = UUID.randomUUID();
        var valid = fixtureDocument(candidate(session, "valid", VisualSearchTrialResult.Outcome.SUCCESS));
        var invalid = fixtureDocument(candidate(session, "invalid", VisualSearchTrialResult.Outcome.SUCCESS));
        invalid.get("result", Document.class).put("confirmedDwellMs", null);
        seed(List.of(valid, invalid));
        var before = evidenceSnapshot();
        var failure = assertThrows(IllegalStateException.class,
                () -> reader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        assertEquals("Visual Search result read unavailable", failure.getMessage()); assertNull(failure.getCause());
        var result = summaryService(session).query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC,
                "synthetic-doctor-token").await().atMost(WAIT);
        assertEquals(VisualSearchTrialSummaryService.Status.UNAVAILABLE, result.status()); assertNull(result.summary());
        assertTrue(before.equals(evidenceSnapshot()), "Rejected fixture was changed");
    }

    @Test void standardScanningInsertsAndReadsAllThreeOutcomesExactly() {
        requireScanningAdapter(); var visualSearchBefore = evidenceSnapshot();
        UUID session = UUID.randomUUID();
        for (var outcome : VisualSearchTrialResult.Outcome.values()) {
            var candidate = scanningCandidate(session, outcome.name(), outcome);
            assertTrue(scanningStore.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = scanningStore.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created()); assertTrue(candidate.equals(write.stored()), "Inserted scanning value differs");
            assertTrue(scanningStore.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Scanning round-trip value differs");
            assertEquals(1L, scanningCount(candidate));
            assertScanningFormat(candidate, scanningDocument(candidate));
        }
        assertEquals(3L, await(scanningRows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning changed Visual Search evidence");
    }

    @Test void standardScanningIdenticalReplayKeepsOneUnchangedDocument() {
        requireScanningAdapter(); var visualSearchBefore = evidenceSnapshot();
        var candidate = scanningCandidate(UUID.randomUUID(), "opaque/試行 : 1", VisualSearchTrialResult.Outcome.SUCCESS);
        assertTrue(scanningStore.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = scanningDocument(candidate);
        var replay = scanningStore.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created()); assertTrue(candidate.equals(replay.stored()), "Scanning replay winner differs");
        assertScanningUnchanged(candidate, before);
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning changed Visual Search evidence");
    }

    @Test void standardScanningDifferentResultReturnsOriginalWinnerWithoutOverwrite() {
        requireScanningAdapter(); var visualSearchBefore = evidenceSnapshot(); UUID session = UUID.randomUUID();
        var first = scanningCandidate(session, "conflict", VisualSearchTrialResult.Outcome.SUCCESS);
        var second = scanningCandidate(session, "conflict", VisualSearchTrialResult.Outcome.TIMEOUT);
        assertTrue(scanningStore.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = scanningDocument(first);
        var conflict = scanningStore.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created()); assertTrue(first.equals(conflict.stored()), "Scanning conflict replaced its winner");
        assertScanningUnchanged(first, before);
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning changed Visual Search evidence");
    }

    @Test void standardScanningConcurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        requireScanningAdapter(); var visualSearchBefore = evidenceSnapshot();
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = scanningCandidate(session, "race", VisualSearchTrialResult.Outcome.SUCCESS);
            var second = equal ? first : scanningCandidate(session, "race", VisualSearchTrialResult.Outcome.TIMEOUT);
            var start = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
            try {
                Future<StandardScanningTrialResultStore.WriteResult> left = workers.submit(() -> scanningInsertTogether(start, first));
                Future<StandardScanningTrialResultStore.WriteResult> right = workers.submit(() -> scanningInsertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS); var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one scanning insert must create a record");
                var winner = a.created() ? a.stored() : b.stored(); var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Scanning winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Scanning race returned different winners");
                assertTrue(scanningStore.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, scanningCount(first)); assertScanningFormat(winner, scanningDocument(winner));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned scanning workers did not stop");
            }
        }
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning changed Visual Search evidence");
    }

    @Test void sustainedFixationInsertsAndReadsAllThreeOutcomesExactly() {
        requireFixationAdapter(); var siblingsBefore = siblingEvidenceSnapshots();
        UUID session = UUID.randomUUID();
        for (var outcome : VisualSearchTrialResult.Outcome.values()) {
            var candidate = fixationCandidate(session, outcome.name(), outcome);
            assertTrue(fixationStore.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = fixationStore.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created()); assertTrue(candidate.equals(write.stored()), "Inserted fixation value differs");
            assertTrue(fixationStore.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Fixation round-trip value differs");
            assertEquals(1L, fixationCount(candidate));
            assertFixationFormat(candidate, fixationDocument(candidate));
        }
        assertEquals(3L, await(fixationRows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
        assertTrue(siblingsBefore.equals(siblingEvidenceSnapshots()), "Fixation changed another modality's evidence");
    }

    @Test void sustainedFixationIdenticalReplayKeepsOneUnchangedDocument() {
        requireFixationAdapter(); var siblingsBefore = siblingEvidenceSnapshots();
        var candidate = fixationCandidate(UUID.randomUUID(), "opaque/試行 : 1", VisualSearchTrialResult.Outcome.SUCCESS);
        assertTrue(fixationStore.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = fixationDocument(candidate);
        var replay = fixationStore.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created()); assertTrue(candidate.equals(replay.stored()), "Fixation replay winner differs");
        assertFixationUnchanged(candidate, before);
        assertTrue(siblingsBefore.equals(siblingEvidenceSnapshots()), "Fixation changed another modality's evidence");
    }

    @Test void sustainedFixationDifferentResultReturnsOriginalWinnerWithoutOverwrite() {
        requireFixationAdapter(); var siblingsBefore = siblingEvidenceSnapshots(); UUID session = UUID.randomUUID();
        var first = fixationCandidate(session, "conflict", VisualSearchTrialResult.Outcome.SUCCESS);
        var second = fixationCandidate(session, "conflict", VisualSearchTrialResult.Outcome.TIMEOUT);
        assertTrue(fixationStore.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = fixationDocument(first);
        var conflict = fixationStore.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created()); assertTrue(first.equals(conflict.stored()), "Fixation conflict replaced its winner");
        assertFixationUnchanged(first, before);
        assertTrue(siblingsBefore.equals(siblingEvidenceSnapshots()), "Fixation changed another modality's evidence");
    }

    @Test void sustainedFixationConcurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        requireFixationAdapter(); var siblingsBefore = siblingEvidenceSnapshots();
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = fixationCandidate(session, "race", VisualSearchTrialResult.Outcome.SUCCESS);
            var second = equal ? first : fixationCandidate(session, "race", VisualSearchTrialResult.Outcome.TIMEOUT);
            var start = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
            try {
                Future<SustainedFixationTrialResultStore.WriteResult> left = workers.submit(() -> fixationInsertTogether(start, first));
                Future<SustainedFixationTrialResultStore.WriteResult> right = workers.submit(() -> fixationInsertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS); var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one fixation insert must create a record");
                var winner = a.created() ? a.stored() : b.stored(); var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Fixation winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Fixation race returned different winners");
                assertTrue(fixationStore.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, fixationCount(first)); assertFixationFormat(winner, fixationDocument(winner));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned fixation workers did not stop");
            }
        }
        assertTrue(siblingsBefore.equals(siblingEvidenceSnapshots()), "Fixation changed another modality's evidence");
    }

    @Test void compensatorySaccadeInsertsAndReadsAllThreeOutcomesExactly() {
        requireSaccadeAdapter(); var siblingsBefore = saccadeSiblingEvidenceSnapshots();
        UUID session = UUID.randomUUID();
        for (var outcome : VisualSearchTrialResult.Outcome.values()) {
            var candidate = saccadeCandidate(session, outcome.name(), outcome);
            assertTrue(saccadeStore.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = saccadeStore.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created()); assertTrue(candidate.equals(write.stored()), "Inserted Compensatory Saccade value differs");
            assertTrue(saccadeStore.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Compensatory Saccade round-trip value differs");
            assertEquals(1L, saccadeCount(candidate));
            assertSaccadeFormat(candidate, saccadeDocument(candidate));
        }
        assertEquals(3L, await(saccadeRows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
        assertTrue(siblingsBefore.equals(saccadeSiblingEvidenceSnapshots()), "Compensatory Saccade changed another modality's evidence");
    }

    @Test void compensatorySaccadeIdenticalReplayKeepsOneUnchangedDocument() {
        requireSaccadeAdapter(); var siblingsBefore = saccadeSiblingEvidenceSnapshots();
        var candidate = saccadeCandidate(UUID.randomUUID(), "opaque /: trial 1", VisualSearchTrialResult.Outcome.SUCCESS);
        assertTrue(saccadeStore.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = saccadeDocument(candidate);
        var replay = saccadeStore.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created()); assertTrue(candidate.equals(replay.stored()), "Compensatory Saccade replay winner differs");
        assertSaccadeUnchanged(candidate, before);
        assertTrue(siblingsBefore.equals(saccadeSiblingEvidenceSnapshots()), "Compensatory Saccade changed another modality's evidence");
    }

    @Test void compensatorySaccadeDifferentResultReturnsOriginalWinnerWithoutOverwrite() {
        requireSaccadeAdapter(); var siblingsBefore = saccadeSiblingEvidenceSnapshots(); UUID session = UUID.randomUUID();
        var first = saccadeCandidate(session, "conflict", VisualSearchTrialResult.Outcome.SUCCESS);
        var second = saccadeCandidate(session, "conflict", VisualSearchTrialResult.Outcome.TIMEOUT);
        assertTrue(saccadeStore.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = saccadeDocument(first);
        var conflict = saccadeStore.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created()); assertTrue(first.equals(conflict.stored()), "Compensatory Saccade conflict replaced its winner");
        assertSaccadeUnchanged(first, before);
        assertTrue(siblingsBefore.equals(saccadeSiblingEvidenceSnapshots()), "Compensatory Saccade changed another modality's evidence");
    }

    @Test void compensatorySaccadeConcurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        requireSaccadeAdapter(); var siblingsBefore = saccadeSiblingEvidenceSnapshots();
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = saccadeCandidate(session, "race", VisualSearchTrialResult.Outcome.SUCCESS);
            var second = equal ? first : saccadeCandidate(session, "race", VisualSearchTrialResult.Outcome.TIMEOUT);
            var start = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
            try {
                Future<CompensatorySaccadeTrialResultStore.WriteResult> left = workers.submit(() -> saccadeInsertTogether(start, first));
                Future<CompensatorySaccadeTrialResultStore.WriteResult> right = workers.submit(() -> saccadeInsertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS); var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one Compensatory Saccade insert must create a record");
                var winner = a.created() ? a.stored() : b.stored(); var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Compensatory Saccade winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Compensatory Saccade race returned different winners");
                assertTrue(saccadeStore.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, saccadeCount(first)); assertSaccadeFormat(winner, saccadeDocument(winner));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned Compensatory Saccade workers did not stop");
            }
        }
        assertTrue(siblingsBefore.equals(saccadeSiblingEvidenceSnapshots()), "Compensatory Saccade changed another modality's evidence");
    }

    @Test void systematicExplorationInsertsAndReadsAllThreeOutcomesExactly() {
        requireSystematicAdapter(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        UUID session = UUID.randomUUID();
        for (var outcome : SystematicExplorationTrialResult.Outcome.values()) {
            var candidate = systematicCandidate(session, outcome.name(), outcome);
            assertTrue(systematicStore.find(session, candidate.result().trialId()).await().atMost(WAIT).isEmpty());
            var write = systematicStore.insertIfAbsent(candidate).await().atMost(WAIT);
            assertTrue(write.created()); assertTrue(candidate.equals(write.stored()), "Inserted Systematic Exploration value differs");
            assertTrue(systematicStore.find(session, candidate.result().trialId()).await().atMost(WAIT)
                    .filter(candidate::equals).isPresent(), "Systematic Exploration round-trip value differs");
            assertEquals(1L, systematicCount(candidate));
            assertSystematicFormat(candidate, systematicDocument(candidate));
        }
        assertEquals(3L, await(systematicRows().countDocuments(new Document("_id.therapySessionId", session.toString()))));
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Systematic Exploration changed another modality's evidence");
    }

    @Test void systematicExplorationIdenticalReplayKeepsOneUnchangedDocument() {
        requireSystematicAdapter(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        var candidate = systematicCandidate(UUID.randomUUID(), "opaque /: trial 1", SystematicExplorationTrialResult.Outcome.COMPLETED);
        assertTrue(systematicStore.insertIfAbsent(candidate).await().atMost(WAIT).created());
        Document before = systematicDocument(candidate);
        var replay = systematicStore.insertIfAbsent(candidate).await().atMost(WAIT);
        assertFalse(replay.created()); assertTrue(candidate.equals(replay.stored()), "Systematic Exploration replay winner differs");
        assertSystematicUnchanged(candidate, before);
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Systematic Exploration changed another modality's evidence");
    }

    @Test void systematicExplorationDifferentResultReturnsOriginalWinnerWithoutOverwrite() {
        requireSystematicAdapter(); var siblingsBefore = systematicSiblingEvidenceSnapshots(); UUID session = UUID.randomUUID();
        var first = systematicCandidate(session, "conflict", SystematicExplorationTrialResult.Outcome.COMPLETED);
        var second = systematicCandidate(session, "conflict", SystematicExplorationTrialResult.Outcome.TIMED_OUT);
        assertTrue(systematicStore.insertIfAbsent(first).await().atMost(WAIT).created());
        Document before = systematicDocument(first);
        var conflict = systematicStore.insertIfAbsent(second).await().atMost(WAIT);
        assertFalse(conflict.created()); assertTrue(first.equals(conflict.stored()), "Systematic Exploration conflict replaced its winner");
        assertSystematicUnchanged(first, before);
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Systematic Exploration changed another modality's evidence");
    }

    @Test void systematicExplorationConcurrentEqualAndDifferentCandidatesHaveExactlyOneWinner() throws Exception {
        requireSystematicAdapter(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        for (boolean equal : new boolean[]{true, false}) {
            UUID session = UUID.randomUUID();
            var first = systematicCandidate(session, "race", SystematicExplorationTrialResult.Outcome.COMPLETED);
            var second = equal ? first : systematicCandidate(session, "race", SystematicExplorationTrialResult.Outcome.TIMED_OUT);
            var start = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
            try {
                Future<SystematicExplorationTrialResultStore.WriteResult> left = workers.submit(() -> systematicInsertTogether(start, first));
                Future<SystematicExplorationTrialResultStore.WriteResult> right = workers.submit(() -> systematicInsertTogether(start, second));
                var a = left.get(12, TimeUnit.SECONDS); var b = right.get(12, TimeUnit.SECONDS);
                assertTrue(a.created() != b.created(), "Exactly one Systematic Exploration insert must create a record");
                var winner = a.created() ? a.stored() : b.stored(); var expected = a.created() ? first : second;
                assertTrue(expected.equals(winner), "Systematic Exploration winner differs from its candidate");
                assertTrue(winner.equals(a.stored()) && winner.equals(b.stored()), "Systematic Exploration race returned different winners");
                assertTrue(systematicStore.find(session, "race").await().atMost(WAIT).filter(winner::equals).isPresent());
                assertEquals(1L, systematicCount(first)); assertSystematicFormat(winner, systematicDocument(winner));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS), "Owned Systematic Exploration workers did not stop");
            }
        }
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Systematic Exploration changed another modality's evidence");
    }

    private void requireSystematicAdapter() {
        VisualSearchTrialStoreMongoProfile.requireOptIn();
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertInstanceOf(MongoSystematicExplorationTrialResultStoreAdapter.class, systematicStore);
        assertEquals("systematic_exploration_trial_results_it", systematicCollection);
        assertNotEquals(collection, systematicCollection);
        assertNotEquals(scanningCollection, systematicCollection);
        assertNotEquals(fixationCollection, systematicCollection);
        assertNotEquals(saccadeCollection, systematicCollection);
    }
    private SystematicExplorationTrialResultStore.Stored systematicCandidate(UUID session, String trial,
            SystematicExplorationTrialResult.Outcome outcome) {
        var visits = outcome == SystematicExplorationTrialResult.Outcome.COMPLETED
                ? java.util.Arrays.stream(SystematicExplorationTrialResult.Quadrant.values())
                    .map(q -> new SystematicExplorationTrialResult.Visit(q, 1000, 500)).toList()
                : List.of(new SystematicExplorationTrialResult.Visit(
                    SystematicExplorationTrialResult.Quadrant.TOP_LEFT, 1000, 500));
        long elapsed = outcome == SystematicExplorationTrialResult.Outcome.INTERRUPTED ? 1500 : 4000;
        return new SystematicExplorationTrialResultStore.Stored(
                new SystematicExplorationTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new SystematicExplorationTrialResult(session, trial, VisualSearchTrialResult.DataOrigin.SYNTHETIC,
                        outcome, elapsed, visits));
    }
    private SystematicExplorationTrialResultStore.WriteResult systematicInsertTogether(CyclicBarrier start,
            SystematicExplorationTrialResultStore.Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS); return systematicStore.insertIfAbsent(candidate).await().atMost(WAIT);
    }
    private MongoCollection<Document> systematicRows() {
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("systematic_exploration_trial_results_it", systematicCollection);
        return client.unwrap().getDatabase(database).getCollection(systematicCollection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }
    private Document systematicFilter(SystematicExplorationTrialResultStore.Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long systematicCount(SystematicExplorationTrialResultStore.Stored value) {
        return await(systematicRows().countDocuments(systematicFilter(value)));
    }
    private Document systematicDocument(SystematicExplorationTrialResultStore.Stored value) {
        Document row = await(systematicRows().find(systematicFilter(value)).first());
        assertNotNull(row, "Expected Systematic Exploration document is missing"); return row;
    }
    private void assertSystematicUnchanged(SystematicExplorationTrialResultStore.Stored value, Document before) {
        assertEquals(1L, systematicCount(value));
        assertTrue(before.equals(systematicDocument(value)), "Stored Systematic Exploration document changed");
    }
    private void assertSystematicFormat(SystematicExplorationTrialResultStore.Stored value, Document actual) {
        assertTrue(systematicFixtureDocument(value).equals(actual), "Systematic Exploration document shape or frozen values differ");
        assertEquals(List.of("therapySessionId", "trialId"),
                new ArrayList<>(actual.get("_id", Document.class).keySet()));
    }

    private Document systematicFixtureDocument(SystematicExplorationTrialResultStore.Stored value) {
        // Independent persisted-format expectation, not the production encoder/decoder.
        var context = value.context(); var result = value.result();
        var visits = result.visits().stream().map(visit -> new Document("quadrant", visit.quadrant().name())
                .append("timeToVisitMs", visit.timeToVisitMs()).append("confirmedDwellMs", visit.confirmedDwellMs())).toList();
        return new Document("_id", systematicFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs())
                        .append("maximumStimulusDurationMs", context.maximumStimulusDurationMs()))
                .append("result", new Document("outcome", result.outcome().name())
                        .append("elapsedMs", result.elapsedMs()).append("visits", visits)
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private List<List<String>> systematicSiblingEvidenceSnapshots() {
        return List.of(evidenceSnapshot(), scanningEvidenceSnapshot(), fixationEvidenceSnapshot(), saccadeEvidenceSnapshot());
    }

    @Test void standardScanningReaderFiltersExactSessionAndOriginWithoutChangingEvidence() {
        requireScanningReader(); var visualSearchBefore = evidenceSnapshot();
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var fixtures = new ArrayList<StandardScanningTrialResultStore.Stored>();
        for (var origin : VisualSearchTrialResult.DataOrigin.values()) {
            for (UUID scope : List.of(session, otherSession)) {
                var value = scanningWithOrigin(scanningCandidate(scope, "origin-" + origin,
                        VisualSearchTrialResult.Outcome.SUCCESS), origin);
                fixtures.add(value);
            }
        }
        scanningSeed(fixtures.stream().map(this::scanningFixtureDocument).toList());
        var before = scanningEvidenceSnapshot();
        for (var value : fixtures) {
            var actual = scanningReader.read(value.result().therapySessionId(), value.result().dataOrigin(), 1001)
                    .await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(value).equals(actual), "Reader returned another session or origin");
        }
        assertTrue(before.equals(scanningEvidenceSnapshot()), "Filtering changed stored evidence");
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning read changed Visual Search evidence");
    }

    @Test void standardScanningReaderReturnsAllOutcomesExactlyAndEmptyEvidenceWithoutChangingDocuments() {
        requireScanningReader(); var visualSearchBefore = evidenceSnapshot();
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<StandardScanningTrialResultStore.Stored>();
        for (var outcome : VisualSearchTrialResult.Outcome.values())
            expected.add(scanningCandidate(session, "opaque/試行 : " + outcome, outcome));
        scanningSeed(expected.stream().map(this::scanningFixtureDocument).toList());
        var before = scanningEvidenceSnapshot();
        var actual = scanningReader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT);
        assertEquals(3, actual.size());
        assertTrue(new HashSet<>(expected).equals(new HashSet<>(actual)), "Reader round-trip values differ");
        assertThrows(UnsupportedOperationException.class, () -> actual.add(expected.getFirst()));
        assertTrue(scanningReader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty origin returned evidence");
        assertTrue(scanningReader.read(UUID.randomUUID(), VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001)
                .await().atMost(WAIT).isEmpty(), "Empty session returned evidence");
        var summary = scanningSummaryService(session).query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC,
                "synthetic-doctor-token").await().atMost(WAIT);
        assertEquals(StandardScanningTrialSummaryService.Status.AVAILABLE, summary.status());
        assertEquals(3, summary.summary().reportedTrialCount());
        assertEquals(1, summary.summary().detectedTargetCount());
        assertEquals(1, summary.summary().omissionCount()); assertEquals(1, summary.summary().interruptedCount());
        assertEquals(1, summary.summary().eligibleLocalizationCount());
        assertEquals(0, summary.summary().medianLocalizationDurationMs().orElseThrow()
                .compareTo(java.math.BigDecimal.valueOf(1000)));
        var empty = scanningSummaryService(session).query(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED,
                "synthetic-doctor-token").await().atMost(WAIT);
        assertEquals(StandardScanningTrialSummaryService.Status.AVAILABLE, empty.status());
        assertEquals(0, empty.summary().reportedTrialCount()); assertEquals(0, empty.summary().detectedTargetCount());
        assertEquals(0, empty.summary().omissionCount()); assertEquals(0, empty.summary().interruptedCount());
        assertEquals(0, empty.summary().eligibleLocalizationCount());
        assertTrue(empty.summary().medianLocalizationDurationMs().isEmpty());
        assertTrue(before.equals(scanningEvidenceSnapshot()), "Reading changed stored evidence");
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning read changed Visual Search evidence");
    }

    @Test void standardScanningReaderAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        requireScanningReader(); var visualSearchBefore = evidenceSnapshot();
        UUID session = UUID.randomUUID();
        var expected = new ArrayList<StandardScanningTrialResultStore.Stored>();
        for (int i = 0; i < 1000; i++) expected.add(scanningCandidate(session, "bounded-" + i,
                VisualSearchTrialResult.Outcome.SUCCESS));
        scanningSeed(expected.stream().map(this::scanningFixtureDocument).toList());
        var before = scanningEvidenceSnapshot();
        var bounded = scanningReader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 2).await().atMost(WAIT);
        assertEquals(2, bounded.size()); assertEquals(2, new HashSet<>(bounded).size());
        assertTrue(expected.containsAll(bounded), "Small bounded read returned foreign evidence");
        assertEquals(1000, scanningReader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var service = scanningSummaryService(session);
        var result = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(StandardScanningTrialSummaryService.Status.AVAILABLE, result.status());
        assertEquals(1000, result.summary().reportedTrialCount()); assertEquals(1000, result.summary().detectedTargetCount());
        assertEquals(0, result.summary().omissionCount()); assertEquals(0, result.summary().interruptedCount());
        assertEquals(1000, result.summary().eligibleLocalizationCount());
        assertEquals(0, result.summary().medianLocalizationDurationMs().orElseThrow().compareTo(java.math.BigDecimal.valueOf(1000)));
        assertTrue(before.equals(scanningEvidenceSnapshot()), "Bounded reading changed stored evidence");

        // An explicit fixture write, not a side effect of the scanningReader. Keep the prior observation separate.
        scanningSeed(List.of(scanningFixtureDocument(scanningCandidate(session, "overflow-sentinel", VisualSearchTrialResult.Outcome.SUCCESS))));
        var overflowBefore = scanningEvidenceSnapshot();
        assertEquals(1001, scanningReader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, "synthetic-doctor-token")
                .await().atMost(WAIT);
        assertEquals(StandardScanningTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(scanningEvidenceSnapshot()), "Overflow reading changed stored evidence");
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning read changed Visual Search evidence");
    }

    @Test void standardScanningCorruptFixtureIsUnavailableNotEmptyOrPartiallyReturnedAndRemainsUnchanged() {
        requireScanningReader(); var visualSearchBefore = evidenceSnapshot();
        UUID session = UUID.randomUUID();
        var valid = scanningFixtureDocument(scanningCandidate(session, "valid", VisualSearchTrialResult.Outcome.SUCCESS));
        var invalid = scanningFixtureDocument(scanningCandidate(session, "invalid", VisualSearchTrialResult.Outcome.SUCCESS));
        invalid.get("result", Document.class).put("confirmedDwellMs", null);
        scanningSeed(List.of(valid, invalid));
        var before = scanningEvidenceSnapshot();
        var failure = assertThrows(IllegalStateException.class,
                () -> scanningReader.read(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC, 1001).await().atMost(WAIT));
        assertEquals("Standard Scanning result read unavailable", failure.getMessage()); assertNull(failure.getCause());
        var result = scanningSummaryService(session).query(session, VisualSearchTrialResult.DataOrigin.SYNTHETIC,
                "synthetic-doctor-token").await().atMost(WAIT);
        assertEquals(StandardScanningTrialSummaryService.Status.UNAVAILABLE, result.status()); assertNull(result.summary());
        assertTrue(before.equals(scanningEvidenceSnapshot()), "Rejected fixture was changed");
        assertTrue(visualSearchBefore.equals(evidenceSnapshot()), "Scanning read changed Visual Search evidence");
    }

    private void requireScanningReader() {
        requireScanningAdapter();
        assertInstanceOf(MongoStandardScanningTrialResultReaderAdapter.class, scanningReader);
    }

    private StandardScanningTrialSummaryService scanningSummaryService(UUID session) {
        // The scanningReader and MongoDB are real; DOCTOR permission is deliberately simulated, not a JWT proof.
        var context = scanningCandidate(session, "context-only", VisualSearchTrialResult.Outcome.SUCCESS).context();
        return new StandardScanningTrialSummaryService((requested, token) -> {
            assertTrue(session.equals(requested), "Summary requested another session");
            assertEquals("synthetic-doctor-token", token);
            return new StandardScanningTrialSummaryAuthorizationPort.Decision(
                    StandardScanningTrialSummaryAuthorizationPort.Permission.PERMITTED, context);
        }, scanningReader);
    }

    private StandardScanningTrialResultStore.Stored scanningWithOrigin(StandardScanningTrialResultStore.Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new StandardScanningTrialResultStore.Stored(value.context(), new StandardScanningTrialResult(result.schemaVersion(), result.therapySessionId(),
                result.trialId(), result.outcome(), result.localizationDurationMs(), result.confirmedDwellMs(), origin));
    }

    private Document scanningFixtureDocument(StandardScanningTrialResultStore.Stored value) {
        // Explicit synthetic persisted-format fixture, independent of the production encoder/decoder.
        var context = value.context(); var result = value.result();
        return new Document("_id", scanningFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs()).append("maximumStimulusDurationMs", context.maximumStimulusDurationMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("localizationDurationMs", result.localizationDurationMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private void scanningSeed(List<Document> documents) {
        assertTrue(!documents.isEmpty() && documents.size() <= 1001, "Fixture batch is outside its bound");
        var inserted = await(scanningRows().withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).insertMany(documents));
        assertTrue(inserted.wasAcknowledged(), "Fixture insertion was not acknowledged");
        assertEquals(documents.size(), inserted.getInsertedIds().size());
    }

    private List<String> scanningEvidenceSnapshot() {
        // This collection belongs exclusively to this finite test suite, with fewer than 1101 fixture rows.
        var observed = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(scanningRows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(observed.size() < 1101, "Fixture evidence bound exceeded");
        return observed.stream().map(Document::toJson).sorted().toList();
    }

    private void requireScanningAdapter() {
        VisualSearchTrialStoreMongoProfile.requireOptIn();
        assertInstanceOf(MongoStandardScanningTrialResultStoreAdapter.class, scanningStore);
        assertEquals("standard_scanning_trial_results_it", scanningCollection);
        assertNotEquals(collection, scanningCollection);
    }
    private StandardScanningTrialResultStore.Stored scanningCandidate(UUID session, String trial, VisualSearchTrialResult.Outcome outcome) {
        return new StandardScanningTrialResultStore.Stored(new StandardScanningTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new StandardScanningTrialResult(1, session, trial, outcome,
                        outcome == VisualSearchTrialResult.Outcome.TIMEOUT ? 3000 : 1000,
                        outcome == VisualSearchTrialResult.Outcome.SUCCESS ? 500L : null, VisualSearchTrialResult.DataOrigin.SYNTHETIC));
    }
    private StandardScanningTrialResultStore.WriteResult scanningInsertTogether(CyclicBarrier start,
            StandardScanningTrialResultStore.Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS); return scanningStore.insertIfAbsent(candidate).await().atMost(WAIT);
    }
    private MongoCollection<Document> scanningRows() {
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("standard_scanning_trial_results_it", scanningCollection);
        return client.unwrap().getDatabase(database).getCollection(scanningCollection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }
    private Document scanningFilter(StandardScanningTrialResultStore.Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long scanningCount(StandardScanningTrialResultStore.Stored value) {
        return await(scanningRows().countDocuments(scanningFilter(value)));
    }
    private Document scanningDocument(StandardScanningTrialResultStore.Stored value) {
        Document row = await(scanningRows().find(scanningFilter(value)).first());
        assertNotNull(row, "Expected scanning document is missing"); return row;
    }
    private void assertScanningUnchanged(StandardScanningTrialResultStore.Stored value, Document before) {
        assertEquals(1L, scanningCount(value));
        assertTrue(before.equals(scanningDocument(value)), "Stored scanning document changed");
    }
    private void assertScanningFormat(StandardScanningTrialResultStore.Stored value, Document actual) {
        // Independent persisted-format expectation, not the production encoder/decoder.
        var context = value.context(); var result = value.result();
        var expected = new Document("_id", scanningFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs())
                        .append("maximumStimulusDurationMs", context.maximumStimulusDurationMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("localizationDurationMs", result.localizationDurationMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
        assertTrue(expected.equals(actual), "Scanning document shape or frozen values differ");
    }

    private void requireFixationAdapter() {
        VisualSearchTrialStoreMongoProfile.requireOptIn();
        assertInstanceOf(MongoSustainedFixationTrialResultStoreAdapter.class, fixationStore);
        assertEquals("sustained_fixation_trial_results_it", fixationCollection);
        assertNotEquals(collection, fixationCollection);
        assertNotEquals(scanningCollection, fixationCollection);
    }
    private SustainedFixationTrialResultStore.Stored fixationCandidate(UUID session, String trial, VisualSearchTrialResult.Outcome outcome) {
        return new SustainedFixationTrialResultStore.Stored(new SustainedFixationTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new SustainedFixationTrialResult(1, session, trial, outcome,
                        outcome == VisualSearchTrialResult.Outcome.TIMEOUT ? 3000 : 1000,
                        700, outcome == VisualSearchTrialResult.Outcome.SUCCESS ? 500 : 300, VisualSearchTrialResult.DataOrigin.SYNTHETIC));
    }
    private SustainedFixationTrialResultStore.WriteResult fixationInsertTogether(CyclicBarrier start,
            SustainedFixationTrialResultStore.Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS); return fixationStore.insertIfAbsent(candidate).await().atMost(WAIT);
    }
    private MongoCollection<Document> fixationRows() {
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("sustained_fixation_trial_results_it", fixationCollection);
        return client.unwrap().getDatabase(database).getCollection(fixationCollection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }
    private Document fixationFilter(SustainedFixationTrialResultStore.Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long fixationCount(SustainedFixationTrialResultStore.Stored value) {
        return await(fixationRows().countDocuments(fixationFilter(value)));
    }
    private Document fixationDocument(SustainedFixationTrialResultStore.Stored value) {
        Document row = await(fixationRows().find(fixationFilter(value)).first());
        assertNotNull(row, "Expected fixation document is missing"); return row;
    }
    private void assertFixationUnchanged(SustainedFixationTrialResultStore.Stored value, Document before) {
        assertEquals(1L, fixationCount(value));
        assertTrue(before.equals(fixationDocument(value)), "Stored fixation document changed");
    }
    private void assertFixationFormat(SustainedFixationTrialResultStore.Stored value, Document actual) {
        // Independent persisted-format expectation, not the production encoder/decoder.
        var context = value.context(); var result = value.result();
        var expected = new Document("_id", fixationFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs())
                        .append("maximumFixationMs", context.maximumFixationMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("elapsedMs", result.elapsedMs()).append("insideTargetMs", result.insideTargetMs())
                        .append("longestContinuousInsideMs", result.longestContinuousInsideMs())
                        .append("dataOrigin", result.dataOrigin().name()));
        assertTrue(expected.equals(actual), "Fixation document shape or frozen values differ");
        assertEquals(List.of("therapySessionId", "trialId"),
                new ArrayList<>(actual.get("_id", Document.class).keySet()));
    }

    private void requireSaccadeAdapter() {
        VisualSearchTrialStoreMongoProfile.requireOptIn();
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertInstanceOf(MongoCompensatorySaccadeTrialResultStoreAdapter.class, saccadeStore);
        assertEquals("compensatory_saccade_trial_results_it", saccadeCollection);
        assertNotEquals(collection, saccadeCollection);
        assertNotEquals(scanningCollection, saccadeCollection);
        assertNotEquals(fixationCollection, saccadeCollection);
    }
    private CompensatorySaccadeTrialResultStore.Stored saccadeCandidate(UUID session, String trial, VisualSearchTrialResult.Outcome outcome) {
        return new CompensatorySaccadeTrialResultStore.Stored(new CompensatorySaccadeTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new CompensatorySaccadeTrialResult(session, trial, VisualSearchTrialResult.DataOrigin.SYNTHETIC, outcome,
                        outcome == VisualSearchTrialResult.Outcome.TIMEOUT ? 3000 : 1000,
                        outcome == VisualSearchTrialResult.Outcome.SUCCESS ? 500L : null));
    }
    private CompensatorySaccadeTrialResultStore.WriteResult saccadeInsertTogether(CyclicBarrier start,
            CompensatorySaccadeTrialResultStore.Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS); return saccadeStore.insertIfAbsent(candidate).await().atMost(WAIT);
    }
    private MongoCollection<Document> saccadeRows() {
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("compensatory_saccade_trial_results_it", saccadeCollection);
        return client.unwrap().getDatabase(database).getCollection(saccadeCollection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }
    private Document saccadeFilter(CompensatorySaccadeTrialResultStore.Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long saccadeCount(CompensatorySaccadeTrialResultStore.Stored value) {
        return await(saccadeRows().countDocuments(saccadeFilter(value)));
    }
    private Document saccadeDocument(CompensatorySaccadeTrialResultStore.Stored value) {
        Document row = await(saccadeRows().find(saccadeFilter(value)).first());
        assertNotNull(row, "Expected Compensatory Saccade document is missing"); return row;
    }
    private void assertSaccadeUnchanged(CompensatorySaccadeTrialResultStore.Stored value, Document before) {
        assertEquals(1L, saccadeCount(value));
        assertTrue(before.equals(saccadeDocument(value)), "Stored Compensatory Saccade document changed");
    }
    private void assertSaccadeFormat(CompensatorySaccadeTrialResultStore.Stored value, Document actual) {
        assertTrue(saccadeFixtureDocument(value).equals(actual), "Compensatory Saccade document shape or frozen values differ");
        assertEquals(List.of("therapySessionId", "trialId"),
                new ArrayList<>(actual.get("_id", Document.class).keySet()));
    }

    private Document saccadeFixtureDocument(CompensatorySaccadeTrialResultStore.Stored value) {
        // Independent persisted-format expectation, not the production encoder/decoder.
        var context = value.context(); var result = value.result();
        return new Document("_id", saccadeFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs())
                        .append("maximumStimulusDurationMs", context.maximumStimulusDurationMs()))
                .append("result", new Document("outcome", result.outcome().name())
                        .append("elapsedMs", result.elapsedMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private List<List<String>> saccadeSiblingEvidenceSnapshots() {
        return List.of(evidenceSnapshot(), scanningEvidenceSnapshot(), fixationEvidenceSnapshot());
    }

    @Test void compensatorySaccadeReaderFiltersExactSessionAndOrigin() {
        requireSaccadeReader();
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var reported = VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED;
        var fixtures = List.of(
                saccadeCandidate(session, "matching", VisualSearchTrialResult.Outcome.SUCCESS),
                saccadeWithOrigin(saccadeCandidate(session, "other-origin", VisualSearchTrialResult.Outcome.TIMEOUT), reported),
                saccadeCandidate(otherSession, "matching", VisualSearchTrialResult.Outcome.INTERRUPTED),
                saccadeWithOrigin(saccadeCandidate(otherSession, "other-origin", VisualSearchTrialResult.Outcome.SUCCESS), reported));
        saccadeSeed(fixtures);
        var before = saccadeEvidenceSnapshot();

        for (var fixture : fixtures) {
            var result = fixture.result();
            var actual = saccadeReader.read(result.therapySessionId(), result.dataOrigin(), 1001).await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(fixture).equals(actual), "Expected only the scoped Compensatory Saccade attempt");
        }
        assertTrue(before.equals(saccadeEvidenceSnapshot()), "Filtering changed stored evidence");
    }

    @Test void compensatorySaccadeReaderReturnsEmptyForUnobservedOrigin() {
        requireSaccadeReader();
        UUID session = UUID.randomUUID();
        saccadeSeed(List.of(saccadeCandidate(session, "observed", VisualSearchTrialResult.Outcome.SUCCESS)));
        var before = saccadeEvidenceSnapshot();

        var actual = saccadeReader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT);
        assertTrue(actual.isEmpty(), "An unobserved origin must not return another origin's evidence");
        assertTrue(before.equals(saccadeEvidenceSnapshot()), "Empty read changed stored evidence");
    }

    @Test void compensatorySaccadeReaderAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        requireSaccadeReader();
        UUID session = UUID.randomUUID();
        var origin = VisualSearchTrialResult.DataOrigin.SYNTHETIC;
        var expected = new ArrayList<CompensatorySaccadeTrialResultStore.Stored>();
        for (int i = 0; i < 1000; i++)
            expected.add(saccadeCandidate(session, "bounded-" + i, VisualSearchTrialResult.Outcome.SUCCESS));
        saccadeSeed(expected);
        var before = saccadeEvidenceSnapshot();

        var limited = saccadeReader.read(session, origin, 2).await().atMost(WAIT);
        assertEquals(2, limited.size());
        assertEquals(2, new HashSet<>(limited).size());
        assertTrue(expected.containsAll(limited), "Small bounded read returned foreign evidence");
        assertEquals(1000, saccadeReader.read(session, origin, 1001).await().atMost(WAIT).size());
        String token = "synthetic-doctor-token";
        var service = new CompensatorySaccadeTrialSummaryService((requestedSession, originalToken) -> {
            assertTrue(session.equals(requestedSession), "Wrong authorization session");
            assertTrue(token.equals(originalToken), "The original synthetic credential must be forwarded");
            return new CompensatorySaccadeTrialSummaryAuthorizationPort.Decision(
                    CompensatorySaccadeTrialSummaryAuthorizationPort.Permission.PERMITTED, expected.getFirst().context());
        }, saccadeReader);
        var available = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(CompensatorySaccadeTrialSummaryService.Status.AVAILABLE, available.status());
        assertEquals(1000, available.summary().reportedTrialCount());
        assertEquals(1000, available.summary().reachedTargetCount());
        assertEquals(0, available.summary().medianTimeToTargetMs().orElseThrow()
                .compareTo(java.math.BigDecimal.valueOf(1000)));
        assertTrue(before.equals(saccadeEvidenceSnapshot()), "Bounded reading changed stored evidence");

        saccadeSeed(List.of(saccadeCandidate(session, "overflow-sentinel", VisualSearchTrialResult.Outcome.SUCCESS)));
        var overflowBefore = saccadeEvidenceSnapshot();
        assertEquals(1001, saccadeReader.read(session, origin, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(CompensatorySaccadeTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(saccadeEvidenceSnapshot()), "Overflow reading changed stored evidence");
    }

    private void requireSaccadeReader() {
        requireSaccadeAdapter();
        assertInstanceOf(MongoCompensatorySaccadeTrialResultReaderAdapter.class, saccadeReader);
    }

    private static CompensatorySaccadeTrialResultStore.Stored saccadeWithOrigin(
            CompensatorySaccadeTrialResultStore.Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new CompensatorySaccadeTrialResultStore.Stored(value.context(),
                new CompensatorySaccadeTrialResult(result.therapySessionId(), result.trialId(), origin,
                        result.outcome(), result.elapsedMs(), result.confirmedDwellMs()));
    }

    private void saccadeSeed(List<CompensatorySaccadeTrialResultStore.Stored> values) {
        assertTrue(!values.isEmpty() && values.size() <= 1001, "Compensatory Saccade fixture batch is outside its bound");
        var documents = values.stream().map(this::saccadeFixtureDocument).toList();
        assertTrue(await(saccadeRows().withWriteConcern(WriteConcern.MAJORITY).insertMany(documents)).wasAcknowledged(),
                "Compensatory Saccade fixture insertion was not acknowledged");
    }

    private List<String> saccadeEvidenceSnapshot() {
        var documents = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(saccadeRows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(documents.size() < 1101, "Compensatory Saccade fixture evidence bound exceeded");
        return documents.stream().map(Document::toJson).sorted().toList();
    }

    @Test void sustainedFixationReaderFiltersExactSessionAndOrigin() {
        requireFixationReader();
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var reported = VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED;
        var expected = fixationCandidate(session, "matching", VisualSearchTrialResult.Outcome.SUCCESS);
        var otherOrigin = fixationWithOrigin(fixationCandidate(session, "other-origin",
                VisualSearchTrialResult.Outcome.TIMEOUT), reported);
        var otherScope = fixationCandidate(otherSession, "matching", VisualSearchTrialResult.Outcome.INTERRUPTED);
        var bothDifferent = fixationWithOrigin(fixationCandidate(otherSession, "other-origin",
                VisualSearchTrialResult.Outcome.SUCCESS), reported);
        fixationSeed(List.of(expected, otherOrigin, otherScope, bothDifferent));
        var before = fixationEvidenceSnapshot();

        for (var fixture : List.of(expected, otherOrigin, otherScope, bothDifferent)) {
            var result = fixture.result();
            var actual = fixationReader.read(result.therapySessionId(), result.dataOrigin(), 1001).await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(fixture).equals(actual), "Expected only the exact scoped attempt and durations");
        }
        assertTrue(before.equals(fixationEvidenceSnapshot()), "Filtering changed stored evidence");
    }

    @Test void sustainedFixationReaderReturnsEmptyForUnobservedOrigin() {
        requireFixationReader();
        UUID session = UUID.randomUUID();
        var observed = fixationCandidate(session, "observed", VisualSearchTrialResult.Outcome.SUCCESS);
        fixationSeed(List.of(observed));
        var before = fixationEvidenceSnapshot();

        var actual = fixationReader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT);
        assertTrue(actual.isEmpty(), "An unobserved origin must not return another origin's evidence");
        assertTrue(before.equals(fixationEvidenceSnapshot()), "Empty read changed stored evidence");
    }

    @Test void sustainedFixationReaderAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        requireFixationReader();
        UUID session = UUID.randomUUID();
        var origin = VisualSearchTrialResult.DataOrigin.SYNTHETIC;
        var expected = new ArrayList<SustainedFixationTrialResultStore.Stored>();
        for (int i = 0; i < 1000; i++) {
            expected.add(fixationCandidate(session, "bounded-" + i, VisualSearchTrialResult.Outcome.SUCCESS));
        }
        fixationSeed(expected);
        var before = fixationEvidenceSnapshot();
        var limited = fixationReader.read(session, origin, 2).await().atMost(WAIT);
        assertEquals(2, limited.size());
        assertEquals(2, new HashSet<>(limited).size());
        assertTrue(expected.containsAll(limited), "Small bounded read returned foreign evidence");
        assertEquals(1000, fixationReader.read(session, origin, 1001).await().atMost(WAIT).size());

        String token = "synthetic-doctor-token";
        var service = new SustainedFixationTrialSummaryService((requestedSession, originalToken) -> {
            assertEquals(session, requestedSession);
            assertEquals(token, originalToken);
            return new SustainedFixationTrialSummaryAuthorizationPort.Decision(
                    SustainedFixationTrialSummaryAuthorizationPort.Permission.PERMITTED, expected.getFirst().context());
        }, fixationReader);
        var available = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(SustainedFixationTrialSummaryService.Status.AVAILABLE, available.status());
        assertEquals(1000, available.summary().reportedTrialCount());
        assertEquals(1000, available.summary().completedTargetCount());
        assertEquals(700_000, available.summary().totalInsideTargetMs());
        assertTrue(before.equals(fixationEvidenceSnapshot()), "Bounded reading changed stored evidence");

        fixationSeed(List.of(fixationCandidate(session, "overflow-sentinel", VisualSearchTrialResult.Outcome.SUCCESS)));
        var overflowBefore = fixationEvidenceSnapshot();
        assertEquals(1001, fixationReader.read(session, origin, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(SustainedFixationTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(fixationEvidenceSnapshot()), "Overflow reading changed stored evidence");
    }

    private void requireFixationReader() {
        requireFixationAdapter();
        assertInstanceOf(MongoSustainedFixationTrialResultReaderAdapter.class, fixationReader);
    }

    private static SustainedFixationTrialResultStore.Stored fixationWithOrigin(
            SustainedFixationTrialResultStore.Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new SustainedFixationTrialResultStore.Stored(value.context(),
                new SustainedFixationTrialResult(result.schemaVersion(), result.therapySessionId(), result.trialId(),
                        result.outcome(), result.elapsedMs(), result.insideTargetMs(), result.longestContinuousInsideMs(), origin));
    }

    private void fixationSeed(List<SustainedFixationTrialResultStore.Stored> values) {
        assertTrue(!values.isEmpty() && values.size() <= 1001, "Fixation fixture batch is outside its bound");
        var documents = values.stream().map(this::fixationFixtureDocument).toList();
        assertTrue(await(fixationRows().withWriteConcern(WriteConcern.MAJORITY).insertMany(documents)).wasAcknowledged(),
                "Fixation fixture insertion was not acknowledged");
    }

    private List<String> fixationEvidenceSnapshot() {
        var documents = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(fixationRows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(documents.size() < 1101, "Fixation fixture evidence bound exceeded");
        return documents.stream().map(Document::toJson).sorted().toList();
    }

    private Document fixationFixtureDocument(SustainedFixationTrialResultStore.Stored value) {
        // Independent synthetic fixture in the persisted format.
        var context = value.context(); var result = value.result();
        return new Document("_id", fixationFilter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs())
                        .append("maximumFixationMs", context.maximumFixationMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("elapsedMs", result.elapsedMs()).append("insideTargetMs", result.insideTargetMs())
                        .append("longestContinuousInsideMs", result.longestContinuousInsideMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private List<List<String>> siblingEvidenceSnapshots() {
        return List.of(evidenceSnapshot(), scanningEvidenceSnapshot());
    }

    private VisualSearchTrialSummaryService summaryService(UUID session) {
        // The reader and MongoDB are real; DOCTOR permission is deliberately simulated, not a JWT proof.
        var context = candidate(session, "context-only", VisualSearchTrialResult.Outcome.SUCCESS).context();
        return new VisualSearchTrialSummaryService((requested, token) -> {
            assertTrue(session.equals(requested), "Summary requested another session");
            assertEquals("synthetic-doctor-token", token);
            return new VisualSearchTrialSummaryAuthorizationPort.Decision(
                    VisualSearchTrialSummaryAuthorizationPort.Permission.PERMITTED, context);
        }, reader);
    }

    private Stored withOrigin(Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new Stored(value.context(), new VisualSearchTrialResult(result.schemaVersion(), result.therapySessionId(),
                result.trialId(), result.outcome(), result.searchDurationMs(), result.confirmedDwellMs(), origin));
    }

    private Document fixtureDocument(Stored value) {
        // Explicit synthetic persisted-format fixture, independent of the production encoder/decoder.
        var context = value.context(); var result = value.result();
        return new Document("_id", filter(value).get("_id")).append("storageSchemaVersion", 1)
                .append("context", new Document("configurationId", context.configurationId().toString())
                        .append("configurationVersion", context.configurationVersion())
                        .append("minimumFixationMs", context.minimumFixationMs()).append("maximumSearchTimeMs", context.maximumSearchTimeMs()))
                .append("result", new Document("schemaVersion", result.schemaVersion()).append("outcome", result.outcome().name())
                        .append("searchDurationMs", result.searchDurationMs()).append("confirmedDwellMs", result.confirmedDwellMs())
                        .append("dataOrigin", result.dataOrigin().name()));
    }

    private void seed(List<Document> documents) {
        assertTrue(!documents.isEmpty() && documents.size() <= 1001, "Fixture batch is outside its bound");
        var inserted = await(rows().withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).insertMany(documents));
        assertTrue(inserted.wasAcknowledged(), "Fixture insertion was not acknowledged");
        assertEquals(documents.size(), inserted.getInsertedIds().size());
    }

    private List<String> evidenceSnapshot() {
        // This collection belongs exclusively to this finite test suite, with at most 1021 fixture rows.
        var observed = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(rows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(observed.size() < 1101, "Fixture evidence bound exceeded");
        return observed.stream().map(Document::toJson).sorted().toList();
    }

    private WriteResult insertTogether(CyclicBarrier start, Stored candidate) throws Exception {
        start.await(2, TimeUnit.SECONDS);
        return store.insertIfAbsent(candidate).await().atMost(WAIT);
    }

    private Stored candidate(UUID session, String trial, VisualSearchTrialResult.Outcome outcome) {
        return new Stored(new VisualSearchTrialContext(session, new UUID(0, 2), 3, 500, 3000),
                new VisualSearchTrialResult(1, session, trial, outcome,
                        outcome == VisualSearchTrialResult.Outcome.TIMEOUT ? 3000 : 1000,
                        outcome == VisualSearchTrialResult.Outcome.SUCCESS ? 500L : null,
                        VisualSearchTrialResult.DataOrigin.SYNTHETIC));
    }

    private MongoCollection<Document> rows() {
        // The existing resource generates this database; direct system-property overrides are refused.
        assertTrue(database.startsWith("analytics_streams_it_"), "Database is not resource-owned");
        assertEquals("visual_search_trial_results_it", collection);
        return client.unwrap().getDatabase(database).getCollection(collection)
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withTimeout(5000, TimeUnit.MILLISECONDS);
    }

    private Document filter(Stored value) {
        return new Document("_id", new Document("therapySessionId", value.result().therapySessionId().toString())
                .append("trialId", value.result().trialId()));
    }
    private long count(Stored value) { return await(rows().countDocuments(filter(value))); }
    private Document document(Stored value) {
        Document row = await(rows().find(filter(value)).first());
        assertNotNull(row, "Expected stored document is missing");
        return row;
    }
    private void assertUnchanged(Stored value, Document before) {
        assertEquals(1L, count(value));
        assertTrue(before.equals(document(value)), "Stored document changed");
    }
    private <T> T await(Publisher<T> publisher) {
        return Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher)).await().atMost(WAIT);
    }

    @Test void systematicExplorationReaderFiltersExactSessionAndOrigin() {
        requireSystematicReader(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        var reported = VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED;
        var fixtures = List.of(
                systematicCandidate(session, "matching", SystematicExplorationTrialResult.Outcome.COMPLETED),
                systematicWithOrigin(systematicCandidate(session, "other-origin", SystematicExplorationTrialResult.Outcome.TIMED_OUT), reported),
                systematicCandidate(otherSession, "matching", SystematicExplorationTrialResult.Outcome.INTERRUPTED),
                systematicWithOrigin(systematicCandidate(otherSession, "other-origin", SystematicExplorationTrialResult.Outcome.COMPLETED), reported));
        systematicSeed(fixtures); var before = systematicEvidenceSnapshot();
        for (var fixture : fixtures) {
            var result = fixture.result();
            var actual = systematicReader.read(result.therapySessionId(), result.dataOrigin(), 1001).await().atMost(WAIT);
            assertEquals(1, actual.size());
            assertTrue(List.of(fixture).equals(actual), "Reader returned another Systematic Exploration session or origin");
        }
        assertTrue(before.equals(systematicEvidenceSnapshot()), "Filtering changed stored evidence");
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Reading changed another modality's evidence");
    }

    @Test void systematicExplorationReaderReturnsEmptyForUnobservedOrigin() {
        requireSystematicReader(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        UUID session = UUID.randomUUID();
        systematicSeed(List.of(systematicCandidate(session, "observed", SystematicExplorationTrialResult.Outcome.COMPLETED)));
        var before = systematicEvidenceSnapshot();
        var actual = systematicReader.read(session, VisualSearchTrialResult.DataOrigin.PRODUCER_REPORTED, 1001)
                .await().atMost(WAIT);
        assertTrue(actual.isEmpty(), "An unobserved origin must not return another origin's evidence");
        assertTrue(before.equals(systematicEvidenceSnapshot()), "Empty read changed stored evidence");
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Reading changed another modality's evidence");
    }

    @Test void systematicExplorationReaderAndSummaryEnforceTheThousandTrialCeilingWithoutAPartialSummary() {
        requireSystematicReader(); var siblingsBefore = systematicSiblingEvidenceSnapshots();
        UUID session = UUID.randomUUID(); var origin = VisualSearchTrialResult.DataOrigin.SYNTHETIC;
        var expected = new ArrayList<SystematicExplorationTrialResultStore.Stored>();
        for (int i = 0; i < 1000; i++)
            expected.add(systematicCandidate(session, "bounded-" + i, SystematicExplorationTrialResult.Outcome.COMPLETED));
        systematicSeed(expected); var before = systematicEvidenceSnapshot();
        var limited = systematicReader.read(session, origin, 2).await().atMost(WAIT);
        assertEquals(2, limited.size()); assertEquals(2, new HashSet<>(limited).size());
        assertTrue(expected.containsAll(limited), "Small bounded read returned foreign evidence");
        assertEquals(1000, systematicReader.read(session, origin, 1001).await().atMost(WAIT).size());
        String token = "synthetic-doctor-token";
        // MongoDB and the reader are real; DOCTOR authorization is deliberately simulated.
        var service = new SystematicExplorationTrialSummaryService((requestedSession, originalToken) -> {
            assertTrue(session.equals(requestedSession), "Wrong authorization session");
            assertTrue(token.equals(originalToken), "Original synthetic credential was not forwarded");
            return new SystematicExplorationTrialSummaryAuthorizationPort.Decision(
                    SystematicExplorationTrialSummaryAuthorizationPort.Permission.PERMITTED, expected.getFirst().context());
        }, systematicReader);
        var available = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(SystematicExplorationTrialSummaryService.Status.AVAILABLE, available.status());
        var summary = available.summary(); assertEquals(1000, summary.reportedTrialCount());
        assertEquals(1000, summary.traversals().size());
        var metrics = summary.traversals().stream().collect(java.util.stream.Collectors.toMap(
                traversal -> traversal.trialId(), traversal -> traversal));
        for (var fixture : expected) {
            var traversal = metrics.get(fixture.result().trialId()); assertNotNull(traversal);
            assertEquals(SystematicExplorationTrialResult.Outcome.COMPLETED, traversal.outcome());
            assertEquals(4, traversal.visitedRegionCount()); assertEquals(100, traversal.coveragePercent());
            assertEquals(4000, traversal.elapsedMs());
        }
        assertTrue(before.equals(systematicEvidenceSnapshot()), "Bounded reading changed stored evidence");
        systematicSeed(List.of(systematicCandidate(session, "overflow-sentinel", SystematicExplorationTrialResult.Outcome.COMPLETED)));
        var overflowBefore = systematicEvidenceSnapshot();
        assertEquals(1001, systematicReader.read(session, origin, 1001).await().atMost(WAIT).size());
        var overflow = service.query(session, origin, token).await().atMost(WAIT);
        assertEquals(SystematicExplorationTrialSummaryService.Status.LIMIT_EXCEEDED, overflow.status());
        assertNull(overflow.summary());
        assertTrue(overflowBefore.equals(systematicEvidenceSnapshot()), "Overflow reading changed stored evidence");
        assertTrue(siblingsBefore.equals(systematicSiblingEvidenceSnapshots()), "Reading changed another modality's evidence");
    }

    private void requireSystematicReader() {
        requireSystematicAdapter();
        assertInstanceOf(MongoSystematicExplorationTrialResultReaderAdapter.class, systematicReader);
    }

    private static SystematicExplorationTrialResultStore.Stored systematicWithOrigin(
            SystematicExplorationTrialResultStore.Stored value, VisualSearchTrialResult.DataOrigin origin) {
        var result = value.result();
        return new SystematicExplorationTrialResultStore.Stored(value.context(),
                new SystematicExplorationTrialResult(result.therapySessionId(), result.trialId(), origin,
                        result.outcome(), result.elapsedMs(), result.visits()));
    }

    private void systematicSeed(List<SystematicExplorationTrialResultStore.Stored> values) {
        assertTrue(!values.isEmpty() && values.size() <= 1001, "Systematic Exploration fixture batch is outside its bound");
        var documents = values.stream().map(this::systematicFixtureDocument).toList();
        assertTrue(await(systematicRows().withWriteConcern(WriteConcern.MAJORITY).insertMany(documents)).wasAcknowledged(),
                "Systematic Exploration fixture insertion was not acknowledged");
    }

    private List<String> systematicEvidenceSnapshot() {
        var documents = io.smallrye.mutiny.Multi.createFrom().publisher(AdaptersToFlow.publisher(systematicRows().find().limit(1101)))
                .collect().asList().await().atMost(WAIT);
        assertTrue(documents.size() < 1101, "Systematic Exploration fixture evidence bound exceeded");
        return documents.stream().map(Document::toJson).sorted().toList();
    }
}
