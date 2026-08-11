package com.neurovision.rehab.profile.infrastructure.persistence.jpa.repositories;

import com.neurovision.rehab.profile.application.internal.commandservices.MedicalLicenseAlreadyRegisteredException;
import com.neurovision.rehab.profile.application.ports.out.persistence.DoctorProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.infrastructure.persistence.jpa.entities.DoctorProfileEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

@ApplicationScoped
public class JpaDoctorProfileRepository implements DoctorProfileRepository {

    private static final String MEDICAL_LICENSE_CONSTRAINT =
            "uq_doctor_profiles_medical_license";

    private final DoctorProfilePanacheRepository entities;

    public JpaDoctorProfileRepository(DoctorProfilePanacheRepository entities) {
        this.entities = Objects.requireNonNull(entities);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<DoctorProfile> findByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "Profile ID must not be null");
        return Optional.ofNullable(entities.findByProfileId(profileId.value()))
                .map(DoctorProfileEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public void save(DoctorProfile profile) {
        Objects.requireNonNull(profile, "Doctor profile must not be null");
        try {
            entities.findByIdOptional(profile.id().value()).ifPresentOrElse(
                    entity -> entity.updateFrom(profile),
                    () -> entities.persist(DoctorProfileEntity.fromDomain(profile)));
            entities.flush();
        } catch (RuntimeException exception) {
            if (isConstraintViolation(exception, MEDICAL_LICENSE_CONSTRAINT)) {
                throw new MedicalLicenseAlreadyRegisteredException();
            }
            throw exception;
        }
    }

    private static boolean isConstraintViolation(Throwable exception, String constraintName) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
