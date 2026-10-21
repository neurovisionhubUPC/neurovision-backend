package com.neurovision.rehab.profile.application.internal.commandservices;

import com.neurovision.rehab.profile.application.ports.out.persistence.DoctorProfileRepository;
import com.neurovision.rehab.profile.application.ports.out.persistence.UserProfileRepository;
import com.neurovision.rehab.profile.domain.model.aggregates.DoctorProfile;
import com.neurovision.rehab.profile.domain.model.aggregates.UserProfile;
import com.neurovision.rehab.profile.domain.model.valueobjects.CognitoSubject;
import com.neurovision.rehab.profile.domain.model.valueobjects.DoctorProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.PersonalDetails;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileId;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfileType;
import com.neurovision.rehab.profile.domain.model.valueobjects.ProfessionalDetails;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class OnboardDoctorCommandService {

    private final UserProfileRepository userProfiles;
    private final DoctorProfileRepository doctorProfiles;
    private final Supplier<UUID> userProfileIdGenerator;
    private final Supplier<UUID> doctorProfileIdGenerator;
    private final Clock clock;

    public OnboardDoctorCommandService(
            UserProfileRepository userProfiles,
            DoctorProfileRepository doctorProfiles,
            Supplier<UUID> userProfileIdGenerator,
            Supplier<UUID> doctorProfileIdGenerator,
            Clock clock) {
        this.userProfiles = Objects.requireNonNull(userProfiles);
        this.doctorProfiles = Objects.requireNonNull(doctorProfiles);
        this.userProfileIdGenerator = Objects.requireNonNull(userProfileIdGenerator);
        this.doctorProfileIdGenerator = Objects.requireNonNull(doctorProfileIdGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    public OnboardDoctorResult handle(OnboardDoctorCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        var subject = new CognitoSubject(command.cognitoSubject());
        var now = clock.instant();
        var userProfile = userProfiles.findByCognitoSubject(subject)
                .orElseGet(() -> UserProfile.registerDoctor(
                        new ProfileId(userProfileIdGenerator.get()), subject, now));
        if (userProfile.type() != ProfileType.DOCTOR) {
            throw new ProfileTypeConflictException();
        }

        var personalDetails = new PersonalDetails(
                        command.firstName(),
                        command.lastName(),
                        command.documentNumber(),
                        command.birthDate(),
                        command.phone());

        var professionalDetails =
                new ProfessionalDetails(command.specialty(), command.medicalLicense());
        userProfile.complete(personalDetails, now);
        var doctorProfile = doctorProfiles.findByProfileId(userProfile.id())
                .orElseGet(() -> DoctorProfile.create(
                        new DoctorProfileId(doctorProfileIdGenerator.get()),
                        userProfile.id(),
                        professionalDetails,
                        now));
        doctorProfile.update(professionalDetails, now);

        userProfiles.save(userProfile);
        doctorProfiles.save(doctorProfile);
        return new OnboardDoctorResult(userProfile, doctorProfile);
    }
}
