package com.neurovision.rehab.clinical.infrastructure.persistence.jpa.repositories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionActivityRepository;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;

/** The caller holds the shared session lock; receipt and progress writes commit with the session. */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JpaSessionActivityRepository implements SessionActivityRepository {
    private final EntityManager em;
    private final ObjectMapper mapper;
    public JpaSessionActivityRepository(EntityManager em, ObjectMapper mapper) { this.em = em; this.mapper = mapper; }
    private <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored progress unavailable", invalid); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Progress serialization unavailable", invalid); }
    }
    private TherapySessionProgressEntity entity(TherapySessionId id) { return em.find(TherapySessionProgressEntity.class, id.value()); }
    private TherapySessionProgressEntity create(TherapySessionId id) {
        var current = entity(id);
        if (current != null) return current;
        var row = new TherapySessionProgressEntity(); row.sessionId = id.value();
        row.state = encode(SessionActivityProgress.empty()); em.persist(row); return row;
    }
    @Override public Optional<SessionActivityProgress> find(TherapySessionId id) {
        return Optional.ofNullable(entity(id)).map(row -> decode(row.state, SessionActivityProgress.class));
    }
    @Override public Optional<Receipt> receipt(TherapySessionId id, UUID event) {
        return Optional.ofNullable(em.find(TherapySessionActivityReceiptEntity.class, id.value() + "/" + event))
                .map(row -> decode(row.receipt, Receipt.class));
    }
    @Override public Optional<Completion> completion(TherapySessionId id) {
        return Optional.ofNullable(entity(id)).map(row -> row.completion).map(json -> decode(json, Completion.class));
    }
    @Override public void save(TherapySessionId id, SessionActivityProgress state) { create(id).state = encode(state); }
    @Override public void append(TherapySessionId id, Receipt receipt) {
        var row = new TherapySessionActivityReceiptEntity(); row.id = id.value() + "/" + receipt.event().eventId();
        row.sessionId = id.value(); row.sequence = receipt.event().sequence(); row.receipt = encode(receipt);
        em.persist(row);
    }
    @Override public void close(TherapySessionId id, Completion completion) {
        var row = create(id);
        if (row.completion != null) throw new IllegalStateException("Completion already recorded");
        row.completion = encode(completion);
    }
}
