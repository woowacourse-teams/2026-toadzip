package com.toadzip.backend.ingest.exception.exception;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import lombok.Getter;

@Getter
public class ExternalDataCallFailureException extends RuntimeException {

    private final String requestDescription;
    private final int attemptCount;

    public ExternalDataCallFailureException(
            String requestDescription,
            int attemptCount,
            RuntimeException cause
    ) {
        super(cause.getMessage(), cause);
        this.requestDescription = requestDescription;
        this.attemptCount = attemptCount;
    }

    public boolean isRateLimited() {
        return getCause() instanceof ExternalDataRequestException exception
                && exception.isRateLimited();
    }
}
