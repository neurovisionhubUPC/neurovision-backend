package com.neurovision.rehab.profile.interfaces.events.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovision.rehab.profile.application.internal.commandservices.RegisterInvitedDoctorCommand;
import com.neurovision.rehab.profile.interfaces.events.contracts.DoctorAccountInvitedV1;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;

@ApplicationScoped
public class DoctorAccountInvitedEventTransformer {

    private final ObjectMapper objectMapper;

    public DoctorAccountInvitedEventTransformer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public RegisterInvitedDoctorCommand toCommand(String payload) {
        try {
            var event = objectMapper.readValue(payload, DoctorAccountInvitedV1.class);
            return new RegisterInvitedDoctorCommand(
                    event.eventId(), event.doctorSubject(), event.occurredAt());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidDoctorAccountInvitedEventException(exception);
        }
    }
}
