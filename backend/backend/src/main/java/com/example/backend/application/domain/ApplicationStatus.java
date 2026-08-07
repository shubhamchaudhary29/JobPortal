package com.example.backend.application.domain;

import java.util.Set;

public enum ApplicationStatus {
    APPLIED,
    IN_REVIEW,
    SHORTLISTED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN;

    public boolean canTransitionTo(ApplicationStatus target) {
        return switch (this) {
            case APPLIED -> Set.of(IN_REVIEW, REJECTED, WITHDRAWN).contains(target);
            case IN_REVIEW -> Set.of(SHORTLISTED, REJECTED, WITHDRAWN).contains(target);
            case SHORTLISTED -> Set.of(ACCEPTED, REJECTED, WITHDRAWN).contains(target);
            case ACCEPTED, REJECTED, WITHDRAWN -> false;
        };
    }
}
