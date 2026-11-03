package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeVisualSearchSessionQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JpaVisualSearchAuthorizationSnapshotRepositoryTest {
    static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    final TherapeuticConfigurationJsonCodec codec = new TherapeuticConfigurationJsonCodec(new ObjectMapper());
    final Map<String, Object> parameters = new HashMap<>();
    List<?> rows = List.of(); String sql; int queries, maxResults, writes;
    final Query query = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class}, (p, method, args) -> {
        return switch (method.getName()) {
            case "setParameter" -> { parameters.put((String) args[0], args[1]); yield p; }
            case "setMaxResults" -> { maxResults = (Integer) args[0]; yield p; }
            case "getResultList" -> rows;
            default -> { writes++; throw new AssertionError("Unexpected persistence operation"); }
        };
    });
    final EntityManager entities = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class}, (p, method, args) -> {
        if (!method.getName().equals("createNativeQuery")) { writes++; throw new AssertionError("Unexpected entity operation"); }
        queries++; sql = (String) args[0]; return query;
    });
    final JpaVisualSearchAuthorizationSnapshotRepository repository = new JpaVisualSearchAuthorizationSnapshotRepository(entities, codec);
    TherapeuticExerciseParameters.VisualSearch search() {
        return new TherapeuticExerciseParameters.VisualSearch(TherapeuticExerciseParameters.VisualSearchMode.TARGET_SEARCH_WITH_DISTRACTORS,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 5, 2, 500, 3000, new BigDecimal("0.5"), 50, 1);
    }
    Object[] row() { return new Object[]{SESSION, "ACTIVE", NOW.plusSeconds(1), CONFIGURATION, 3L, "VISUAL_SEARCH", 1, codec.encode(search())}; }
    Optional<com.neurovision.rehab.clinical.application.ports.out.authorization.VisualSearchAuthorizationSnapshotRepository.Snapshot> read() {
        return repository.findBySubjectAndSession(new CognitoSubject("synthetic-patient"), new TherapySessionId(SESSION));
    }

    @Test void oneBoundedSelectBindsSubjectAssignmentAndFrozenSessionColumnsWithoutWrites() {
        rows = Collections.singletonList(row()); var snapshot = read().orElseThrow();
        assertEquals(1, queries); assertEquals(2, maxResults); assertEquals(0, writes);
        assertEquals(Map.of("sessionId", SESSION, "patientSubject", "synthetic-patient"), parameters);
        String normalized = sql.replaceAll("\\s+", " ");
        for (String predicate : List.of("FROM therapy_sessions s", "JOIN clinical_patients p ON p.id = s.clinical_patient_id",
                "s.id = :sessionId", "p.patient_cognito_sub = :patientSubject", "EXISTS", "a.clinical_patient_id = s.clinical_patient_id",
                "a.doctor_cognito_sub = s.doctor_cognito_sub", "a.status = 'ACTIVE'", "s.configuration_parameters::text"))
            assertTrue(normalized.contains(predicate), "Required snapshot predicate missing");
        assertFalse(normalized.contains("therapy_plans")); assertFalse(normalized.contains("FOR UPDATE"));
        assertEquals(TherapySessionStatus.ACTIVE, snapshot.status()); assertEquals(NOW.plusSeconds(1), snapshot.leaseExpiresAt());
        assertEquals(search(), snapshot.configuration().parameters());
    }

    @Test void schemaTwoRemainsFrozenAndItsPrescriptionIsUnwrappedByTheQueryService() {
        var row = row(); row[6] = 2;
        row[7] = "{\"parameters\":" + codec.encode(search()) + ",\"completionTarget\":{\"criterion\":\"REPETITIONS\",\"target\":5}}";
        rows = Collections.singletonList(row);
        var permit = new AuthorizeVisualSearchSessionQueryService(repository, Clock.fixed(NOW, ZoneOffset.UTC))
                .authorize("synthetic-patient", new TherapySessionId(SESSION));
        assertTrue(permit.mayInsert()); assertEquals(CONFIGURATION, permit.configurationId());
        assertEquals(3, permit.configurationVersion()); assertEquals(500, permit.minimumFixationMs());
        assertEquals(3000, permit.maximumSearchTimeMs()); assertEquals(1, queries); assertEquals(0, writes);
    }

    @Test void supportsActualTimestampRepresentationsAndNullWithoutExpiringSessions() {
        for (Object lease : Arrays.asList(NOW, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), Timestamp.from(NOW), null)) {
            var row = row(); row[2] = lease; rows = Collections.singletonList(row);
            assertEquals(lease == null ? null : NOW, read().orElseThrow().leaseExpiresAt());
        }
        assertEquals(0, writes);
    }

    @Test void missingRowsDenyAndAmbiguousOrCorruptRowsAreUnavailableWithoutRawCauses() {
        assertTrue(read().isEmpty());
        for (int kind = 0; kind < 9; kind++) {
            var row = row();
            switch (kind) {
                case 0 -> row[1] = "PRIVATE_INVALID_STATUS";
                case 1 -> row[2] = "private timestamp";
                case 2 -> row[3] = null;
                case 3 -> row[4] = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(3));
                case 4 -> row[5] = "PRIVATE_EXERCISE";
                case 5 -> row[6] = new BigDecimal("1.5");
                case 6 -> row[6] = 99;
                case 7 -> row[7] = "{\"private\":true}";
                default -> row = new Object[0];
            }
            rows = Collections.singletonList(row);
            var error = assertThrows(IllegalStateException.class, this::read);
            assertEquals("Visual Search authorization snapshot unavailable", error.getMessage()); assertNull(error.getCause());
        }
        rows = List.of(row(), row()); assertThrows(IllegalStateException.class, this::read); assertEquals(0, writes);
    }
}
