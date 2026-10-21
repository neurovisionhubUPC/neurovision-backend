package com.neurovision.rehab.clinical.application.internal.commandservices;

/** Safe dependency failure: no identifiers, credentials or upstream response details. */
public final class DoctorSchedulingUnavailableException extends RuntimeException {
    public DoctorSchedulingUnavailableException() { super("Doctor scheduling eligibility is unavailable"); }
}
