package com.neurovision.rehab.clinical.application.ports.in;

import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import java.time.Instant;
import java.util.UUID;

public interface PatientSessionProgressUseCase {
    enum Action { FINISHED, ABANDONED }
    record View(UUID therapySessionId, String status, CompletionTarget.Criterion criterion, long target,
            long activeMilliseconds, long completedRepetitions, boolean targetReached,
            long nextSequence, SessionActivityProgress.Mode activityState, Instant endedAt) { }
    View activity(CognitoSubject patient, TherapySessionId session, SessionActivityEvent event);
    View progress(CognitoSubject patient, TherapySessionId session);
    View complete(CognitoSubject patient, TherapySessionId session, IdempotencyKey key, Action action);
}
