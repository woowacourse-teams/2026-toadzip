package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestRejectedException;

public final class ExternalDataRateLimit {

    private ExternalDataRateLimit() {
    }

    public static int count(RuntimeException exception) {
        if (exception instanceof ExternalDataCallFailureException failure
                && failure.isRateLimited()) {
            return 1;
        }
        if (exception instanceof ExternalDataRequestException failure
                && failure.isRateLimited()) {
            return 1;
        }
        if (exception instanceof ExternalDataRequestRejectedException failure
                && failure.isRateLimited()) {
            return 1;
        }
        return 0;
    }
}
