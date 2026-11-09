package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.internal.queryservices.AuthorizeDoctorStandardScanningSummaryQueryService;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.json.TherapeuticConfigurationJsonCodec;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JpaDoctorStandardScanningSummarySnapshotRepositoryTest {
    static final UUID SESSION = new UUID(0, 1), CONFIGURATION = new UUID(0, 2);
    final TherapeuticConfigurationJsonCodec codec = new TherapeuticConfigurationJsonCodec(new ObjectMapper());
    final Map<String, Object> parameters = new HashMap<>();
    List<?> rows = List.of(); String sql; int queries, maxResults, unexpected;
    final Query query = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class}, (p, m, a) -> switch (m.getName()) {
        case "setParameter" -> { parameters.put((String) a[0], a[1]); yield p; }
        case "setMaxResults" -> { maxResults = (Integer) a[0]; yield p; }
        case "getResultList" -> rows;
        default -> { unexpected++; throw new AssertionError("Unexpected persistence operation"); }
    });
    final EntityManager entities = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class}, (p, m, a) -> {
        if (!m.getName().equals("createNativeQuery")) { unexpected++; throw new AssertionError("Unexpected entity operation"); }
        queries++; sql = (String) a[0]; return query;
    });
    final JpaDoctorVisualSearchSummarySnapshotRepository repository = new JpaDoctorVisualSearchSummarySnapshotRepository(entities, codec);
    TherapeuticExerciseParameters.VisualScanning scanning() {
        return new TherapeuticExerciseParameters.VisualScanning(TherapeuticExerciseParameters.VisualScanningMode.STANDARD_SCANNING,
                TherapeuticExerciseParameters.AffectedHemifield.LEFT, 500, 3000, 10, 50, 5, 500, 1);
    }
    Object[] row() { return new Object[]{SESSION, CONFIGURATION, 3L, "VISUAL_SCANNING", 1, codec.encode(scanning())}; }
    Optional<com.neurovision.rehab.clinical.application.ports.out.authorization.DoctorVisualSearchSummarySnapshotRepository.Snapshot> read() {
        return repository.findByDoctorAndSession(new CognitoSubject("synthetic-doctor"), new TherapySessionId(SESSION));
    }
    @Test void oneBoundedObservationChecksAuthorshipAssignmentAndFrozenConfigurationWithoutWrites() {
        rows = Collections.singletonList(row()); var snapshot = read().orElseThrow();
        assertEquals(1, queries); assertEquals(2, maxResults); assertEquals(0, unexpected);
        assertEquals(Map.of("sessionId", SESSION, "doctorSubject", "synthetic-doctor"), parameters);
        var normalized = sql.replaceAll("\\s+", " ");
        for (String predicate : List.of("FROM therapy_sessions s", "s.id = :sessionId", "s.doctor_cognito_sub = :doctorSubject",
                "JOIN clinical_patients p ON p.id = s.clinical_patient_id", "EXISTS", "a.clinical_patient_id = s.clinical_patient_id",
                "a.doctor_cognito_sub = :doctorSubject", "a.status = 'ACTIVE'", "s.configuration_parameters::text"))
            assertTrue(normalized.contains(predicate), "Required permission predicate missing");
        for (String forbidden : List.of("therapy_plans", "therapeutic_configurations", "FOR UPDATE", "s.status", "lease_expires_at"))
            assertFalse(normalized.contains(forbidden), "Unexpected live configuration, mutation or session-state gate");
        assertEquals(scanning(), snapshot.configuration().parameters()); assertEquals(new TherapySessionId(SESSION), snapshot.sessionId());
    }
    @Test void schemaTwoIsReadFromTheSessionAndUnwrappedWithoutRequiringAnActiveLease() {
        var row = row(); row[4] = 2; row[5] = "{\"parameters\":" + codec.encode(scanning())
                + ",\"completionTarget\":{\"criterion\":\"REPETITIONS\",\"target\":5}}";
        rows = Collections.singletonList(row);
        var permit = new AuthorizeDoctorStandardScanningSummaryQueryService(repository).authorize("synthetic-doctor", new TherapySessionId(SESSION));
        assertEquals(CONFIGURATION, permit.configurationId()); assertEquals(3, permit.configurationVersion());
        assertEquals(500, permit.minimumFixationMs()); assertEquals(3000, permit.maximumStimulusDurationMs());
        assertEquals(1, queries); assertEquals(0, unexpected);
    }
    @Test void missingAmbiguousAndMalformedObservationsFailClosedWithoutPrivateErrors() {
        assertTrue(read().isEmpty());
        for (int kind = 0; kind < 8; kind++) {
            var row = row();
            switch (kind) {
                case 0 -> row[0] = new UUID(0, 9);
                case 1 -> row[1] = null;
                case 2 -> row[2] = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(3));
                case 3 -> row[3] = "PRIVATE_INVALID_EXERCISE";
                case 4 -> row[4] = new BigDecimal("1.5");
                case 5 -> row[4] = 99;
                case 6 -> row[5] = "{\"private\":true}";
                default -> row = new Object[0];
            }
            rows = Collections.singletonList(row);
            var error = assertThrows(IllegalStateException.class, this::read);
            assertEquals("Visual Search summary snapshot unavailable", error.getMessage()); assertNull(error.getCause());
        }
        rows = List.of(row(), row()); assertThrows(IllegalStateException.class, this::read); assertEquals(0, unexpected);
    }
}
