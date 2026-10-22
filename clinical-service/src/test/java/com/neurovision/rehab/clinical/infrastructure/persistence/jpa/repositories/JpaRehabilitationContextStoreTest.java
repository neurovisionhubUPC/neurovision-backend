package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.domain.model.valueobjects.RehabilitationContext.Change.*;
import com.neurovision.rehab.clinical.application.internal.commandservices.RehabilitationContextFailure;
import com.neurovision.rehab.clinical.application.ports.out.persistence.DoctorPatientAssignmentRepository;
import com.neurovision.rehab.clinical.domain.model.aggregates.ClinicalPatient;
import com.neurovision.rehab.clinical.domain.model.aggregates.DoctorPatientAssignment;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.ClinicalPatientEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises adapter calls and state; no database or transaction interceptor is started. */
class JpaRehabilitationContextStoreTest {
    final UUID id = new UUID(0, 1);
    final ClinicalPatientEntity entity = ClinicalPatientEntity.fromDomain(
            ClinicalPatient.registerInvited(new ClinicalPatientId(id), new CognitoSubject("patient"), Instant.EPOCH));
    final List<String> calls = new ArrayList<>();
    final Map<String, Object> parameters = new HashMap<>();
    boolean assigned = true;
    boolean failFlush;
    boolean failRefresh;
    Instant persistedUpdatedAt;
    String queryText;

    JpaRehabilitationContextStoreTest() throws Exception { setVersion(5); }
    void setVersion(long value) throws Exception {
        var field = ClinicalPatientEntity.class.getDeclaredField("version");
        field.setAccessible(true);
        field.setLong(entity, value);
    }
    final DoctorPatientAssignmentRepository assignments = (DoctorPatientAssignmentRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{DoctorPatientAssignmentRepository.class}, (p, m, a) -> {
                assertEquals("findActiveByClinicalPatientIdAndDoctorSubjectForUpdate", m.getName());
                calls.add("assignment-lock");
                assertEquals(new ClinicalPatientId(id), a[0]);
                assertEquals(new CognitoSubject("doctor"), a[1]);
                return assigned ? Optional.of(DoctorPatientAssignment.assign(
                        new DoctorPatientAssignmentId(new UUID(0, 2)), new ClinicalPatientId(id),
                        new CognitoSubject("doctor"), Instant.EPOCH)) : Optional.empty();
            });
    final EntityManager em = (EntityManager) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{EntityManager.class}, (p, m, a) -> switch (m.getName()) {
                case "find" -> {
                    calls.add("patient-lock");
                    assertEquals(List.of("assignment-lock", "patient-lock"), calls);
                    assertEquals(ClinicalPatientEntity.class, a[0]);
                    assertEquals(id, a[1]);
                    assertEquals(LockModeType.PESSIMISTIC_WRITE, a[2]);
                    yield entity;
                }
                case "flush" -> {
                    calls.add("flush");
                    if (failFlush) throw new IllegalStateException("synthetic-flush");
                    setVersion(entity.version() + 1);
                    yield null;
                }
                case "refresh" -> {
                    calls.add("refresh");
                    if (failRefresh) throw new IllegalStateException("synthetic-refresh");
                    assertSame(entity, a[0]);
                    if (persistedUpdatedAt != null) {
                        var field = ClinicalPatientEntity.class.getDeclaredField("updatedAt");
                        field.setAccessible(true);
                        field.set(entity, persistedUpdatedAt);
                    }
                    yield null;
                }
                case "createQuery" -> {
                    queryText = (String) a[0];
                    yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TypedQuery.class},
                            (q, method, args) -> switch (method.getName()) {
                                case "setParameter" -> { parameters.put((String) args[0], args[1]); yield q; }
                                case "setMaxResults" -> { assertEquals(1, args[0]); yield q; }
                                case "getResultList" -> assigned ? List.of(entity) : List.of();
                                default -> throw new AssertionError(method.getName());
                            });
                }
                default -> throw new AssertionError(m.getName());
            });

    JpaRehabilitationContextStore store() { return new JpaRehabilitationContextStore(em, assignments); }
    RehabilitationContext desired() {
        return entity.rehabilitationContext().apply(new RehabilitationContext.Patch(
                absent(), absent(), absent(), set("manual")));
    }

    @Test void scopedReadReusesTheExactActiveAssignmentPredicate() {
        var result = store().readAssigned(id, "doctor").orElseThrow();
        assertEquals("select p from ClinicalPatientEntity p where p.id=:id and"
                + JpaAssignedPatientReadAdapter.SCOPE, queryText);
        assertEquals(Map.of("id", id, "doctor", "doctor"), parameters);
        assertEquals(5, result.version());
        assertTrue(calls.isEmpty());
        assigned = false;
        assertTrue(store().readAssigned(id, "doctor").isEmpty());
    }
    @Test void assignmentRevocationPreventsPatientLookupAndWrite() {
        assigned = false;
        var before = entity.rehabilitationContext();
        assertTrue(store().replaceAssigned(id, "doctor", 5, desired(), Instant.EPOCH.plusSeconds(1)).isEmpty());
        assertEquals(List.of("assignment-lock"), calls);
        assertEquals(before, entity.rehabilitationContext());
        assertEquals(5, entity.version());
    }
    @Test void staleVersionIsCheckedUnderBothLocksWithoutMutation() {
        var before = entity.rehabilitationContext();
        var error = assertThrows(RehabilitationContextFailure.class,
                () -> store().replaceAssigned(id, "doctor", 4, desired(), Instant.EPOCH.plusSeconds(1)));
        assertEquals(RehabilitationContextFailure.Kind.CONFLICT, error.kind());
        assertEquals(List.of("assignment-lock", "patient-lock"), calls);
        assertEquals(before, entity.rehabilitationContext());
        assertEquals(5, entity.version());
    }
    @Test void updateFlushesBeforeReturningTheNewVersionAndRetainsStatus() {
        var result = store().replaceAssigned(id, "doctor", 5, desired(), Instant.EPOCH.plusSeconds(1)).orElseThrow();
        assertEquals(List.of("assignment-lock", "patient-lock", "flush", "refresh"), calls);
        assertEquals(6, result.version());
        assertEquals("manual", result.context().observations());
        assertEquals(Instant.EPOCH.plusSeconds(1), result.updatedAt());
        assertEquals(ClinicalPatientStatus.ACTIVE, entity.toDomain().status());
        assertEquals(new CognitoSubject("patient"), entity.toDomain().patientSubject());
    }
    @Test void noOpStillReauthorizesAndChecksVersionWithoutChangingTime() {
        var result = store().replaceAssigned(id, "doctor", 5, entity.rehabilitationContext(),
                Instant.EPOCH.plusSeconds(1)).orElseThrow();
        assertEquals(List.of("assignment-lock", "patient-lock"), calls);
        assertEquals(5, result.version());
        assertEquals(Instant.EPOCH, result.updatedAt());
    }
    @Test void changedContextReturnsPersistedTimestampAfterFlushAndRefresh() {
        var requested = Instant.parse("2026-10-21T00:00:00.123456789Z");
        persistedUpdatedAt = Instant.parse("2026-10-21T00:00:00.123457Z");

        var result = store().replaceAssigned(id, "doctor", 5, desired(), requested).orElseThrow();

        assertEquals(List.of("assignment-lock", "patient-lock", "flush", "refresh"), calls);
        assertEquals(persistedUpdatedAt, result.updatedAt());
        assertNotEquals(requested, result.updatedAt());
        assertEquals(6, result.version());
        assertEquals("manual", result.context().observations());
        var readBack = store().readAssigned(id, "doctor").orElseThrow();
        assertEquals(result, readBack);
    }
    @Test void authorizationAndWriteUseTheSameRequiredTransactionBoundary() throws Exception {
        var method = JpaRehabilitationContextStore.class.getMethod("replaceAssigned", UUID.class,
                String.class, long.class, RehabilitationContext.class, Instant.class);
        assertEquals(Transactional.TxType.REQUIRED, method.getAnnotation(Transactional.class).value());
    }
    @Test void refreshFailureCannotReturnSuccessfulSnapshot() {
        failRefresh = true;
        assertThrows(IllegalStateException.class, () -> store().replaceAssigned(id, "doctor", 5, desired(), Instant.EPOCH.plusSeconds(1)));
        assertEquals(List.of("assignment-lock", "patient-lock", "flush", "refresh"), calls);
    }
    @Test void flushFailureCannotRefreshOrReturnSuccessfulSnapshot() {
        failFlush = true;
        assertThrows(IllegalStateException.class, () -> store().replaceAssigned(id, "doctor", 5, desired(), Instant.EPOCH.plusSeconds(1)));
        assertEquals(List.of("assignment-lock", "patient-lock", "flush"), calls);
    }
}
