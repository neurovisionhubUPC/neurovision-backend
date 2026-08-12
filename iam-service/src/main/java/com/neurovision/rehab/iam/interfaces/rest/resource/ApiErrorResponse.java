package com.neurovision.rehab.iam.interfaces.rest.resource;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ApiErrorResponse(String code, String message) {}
