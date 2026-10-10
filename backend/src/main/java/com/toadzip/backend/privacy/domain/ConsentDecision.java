package com.toadzip.backend.privacy.domain;

public enum ConsentDecision {
    UNSET, GRANTED, DENIED, WITHDRAWN;

    public ConsentDecision choose(ConsentAction action) {
        if (action == ConsentAction.GRANT) {
            return GRANTED;
        }
        if (this == GRANTED || this == WITHDRAWN) {
            return WITHDRAWN;
        }
        return DENIED;
    }
}
