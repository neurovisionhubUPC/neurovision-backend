package com.neurovision.rehab.clinical.infrastructure.persistence.json;

import static org.junit.jupiter.api.Assertions.*;
import static com.neurovision.rehab.clinical.support.TherapeuticConfigurationTestData.visualSearchSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.clinical.domain.model.valueobjects.*;
import com.neurovision.rehab.clinical.domain.model.aggregates.TherapeuticExerciseConfiguration;
import com.neurovision.rehab.clinical.infrastructure.persistence.jpa.entities.*;
import com.neurovision.rehab.clinical.application.ports.out.persistence.SessionActivityRepository;
import com.neurovision.rehab.clinical.application.ports.in.PatientSessionProgressUseCase;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompletionConfigurationCompatibilityTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final TherapeuticConfigurationJsonCodec codec = new TherapeuticConfigurationJsonCodec(mapper);
    @Test void roundTripsSchemaOneWithoutAddingAnInferredGoal() {
        var old = visualSearchSnapshot(); var encoded = codec.encode(old.parameters());
        var decoded = codec.decode(old.exercise(), 1, encoded);
        assertEquals(old.parameters(), decoded); assertEquals(encoded, codec.encode(decoded));
        assertFalse(decoded instanceof TherapeuticExerciseParameters.Prescribed);
    }
    @Test void schemaTwoFreezesIndependentTargetAndMaintainsSourceProvenance() throws Exception {
        var old = visualSearchSnapshot();
        for (var criterion : CompletionTarget.Criterion.values()) {
            var parameters = new TherapeuticExerciseParameters.Prescribed(old.parameters(), new CompletionTarget(criterion, 10));
            var encoded = codec.encode(parameters);
            assertEquals(parameters, codec.decode(old.exercise(), 2, encoded));
            assertEquals(mapper.writeValueAsString(parameters), encoded);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(old.exercise(), 1, encoded));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(old.exercise(), 2, codec.encode(old.parameters())));
            var at = Instant.parse("2026-01-01T00:00:00Z");
            var source = TherapeuticExerciseConfiguration.create(old.configurationId(), new TherapyPlanId(UUID.randomUUID()), old.exercise(),
                    2, 2, at, new CognitoSubject("doctor"), at, parameters);
            var entity = TherapeuticExerciseConfigurationEntity.fromDomain(source, codec);
            var reloaded = entity.toDomain(codec);
            assertEquals(parameters, TherapeuticConfigurationSnapshot.from(reloaded).parameters());
        }
    }
    @Test void rejectsMismatchedSchemaAndUnknownTargetFields() throws Exception {
        var old = visualSearchSnapshot();
        var p = new TherapeuticExerciseParameters.Prescribed(old.parameters(), new CompletionTarget(CompletionTarget.Criterion.ACTIVE_TIME, 30));
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(codec.encode(p));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("completionTarget")).put("reportedDuration", 30);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(old.exercise(), 2, json.toString()));
        assertThrows(IllegalArgumentException.class, () -> new TherapeuticConfigurationSnapshot(old.configurationId(), 2, old.exercise(), 1, p));
        assertThrows(IllegalArgumentException.class, () -> new TherapeuticConfigurationSnapshot(old.configurationId(), 2, old.exercise(), 2, old.parameters()));
    }
    @Test void serializesDurableProgressAndReceiptsWithExactReplayValues() throws Exception {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var event = new SessionActivityEvent(1, UUID.randomUUID(), 0, SessionActivityEvent.Type.ACTIVITY_STARTED, null, null);
        var state = SessionActivityProgress.empty().accept(event, at);
        assertEquals(state, mapper.readValue(mapper.writeValueAsString(state), SessionActivityProgress.class));
        var view = new PatientSessionProgressUseCase.View(UUID.randomUUID(), "ACTIVE", CompletionTarget.Criterion.ACTIVE_TIME,
                10, 0, 0, false, 1, SessionActivityProgress.Mode.ACTIVE, null);
        var receipt = new SessionActivityRepository.Receipt(event, view);
        assertEquals(receipt, mapper.readValue(mapper.writeValueAsString(receipt), SessionActivityRepository.Receipt.class));
        var completion = new SessionActivityRepository.Completion("key", PatientSessionProgressUseCase.Action.ABANDONED, view);
        assertEquals(completion, mapper.readValue(mapper.writeValueAsString(completion), SessionActivityRepository.Completion.class));
    }
}
