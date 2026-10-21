package com.neurovision.rehab.profile.interfaces.rest.resource;

import jakarta.validation.Validation;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveProfileRequestTest {
    @Test void requestConstraintsAllowNamesOnlyForBothRoles() {
        try (var factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CompleteProfileRequest("Ana", "Test", null, null, null)).isEmpty());
            assertTrue(validator.validate(new OnboardDoctorRequest("Ana", "Test", null, null, null, null, null)).isEmpty());
            assertFalse(validator.validate(new CompleteProfileRequest(null, "Test", null, null, null)).isEmpty());
            assertFalse(validator.validate(new OnboardDoctorRequest("Ana", " ", null, null, null, null, null)).isEmpty());
            assertFalse(validator.validate(new CompleteProfileRequest("Ana", "Test", "12", null, "short")).isEmpty());
            assertFalse(validator.validate(new CompleteProfileRequest("Ana", "Test", null, LocalDate.now().plusDays(2), null)).isEmpty());
        }
    }
}
