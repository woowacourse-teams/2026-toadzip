package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import lombok.Getter;

@Getter
public class ExternalDataCallFailureException extends RuntimeException {

    private final ExternalDataSource source;
    private final String requestDescription;
    private final int attemptCount;

    public ExternalDataCallFailureException(
            ExternalDataSource source,
            String requestDescription,
            int attemptCount,
            RuntimeException cause
    ) {
        super(cause.getMessage(), cause);
        this.source = source;
        this.requestDescription = requestDescription;
        this.attemptCount = attemptCount;
    }

    public boolean isRateLimited() {
        return getCause() instanceof ExternalDataRequestException exception
                && exception.isRateLimited();
    }
}
