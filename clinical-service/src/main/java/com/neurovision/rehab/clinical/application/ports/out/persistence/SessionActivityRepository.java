package com.neurovision.rehab.clinical.application.ports.out.persistence;

import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase.*;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.util.Optional;

/** All operations occur inside the transaction holding the existing therapy-session row lock. */
public interface SessionActivityRepository {
    record Receipt(SessionActivityEvent event, View acknowledgement) { }
    record Completion(String key, Action action, View acknowledgement) { }
    Optional<SessionActivityProgress> find(TherapySessionId session);
    Optional<Receipt> receipt(TherapySessionId session, java.util.UUID event);
    Optional<Completion> completion(TherapySessionId session);
    void save(TherapySessionId session, SessionActivityProgress progress);
    void append(TherapySessionId session, Receipt receipt);
    void close(TherapySessionId session, Completion completion);
}
