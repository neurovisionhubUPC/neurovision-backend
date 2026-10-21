package com.neurovision.rehab.profile.application.ports.out.authorization;

import java.util.*;

/** Exact target resolution by Clinical after independent DOCTOR authorization. */
public interface AssignedPatientNameAuthorizationPort {
    Map<UUID,String> resolve(Map<UUID,String> claimedTargets, String originalAccessToken);
}
