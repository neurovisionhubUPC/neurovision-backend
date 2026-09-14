package com.neurovision.rehab.clinical.application.internal.commandservices;

public class TherapyPlanRevisionConflictException extends RuntimeException {
    public TherapyPlanRevisionConflictException(String message) { super(message); }
    public TherapyPlanRevisionConflictException(String message, Throwable cause) { super(message, cause); }
}
