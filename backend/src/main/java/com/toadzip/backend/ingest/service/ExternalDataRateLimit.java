package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;

final class ExternalDataRateLimit {

    private ExternalDataRateLimit() {
    }

    static int count(RuntimeException exception) {
        if (exception instanceof ExternalDataCallFailureException failure
                && failure.isRateLimited()) {
            return 1;
        }
        if (exception instanceof ExternalDataRequestException failure
                && failure.isRateLimited()) {
            return 1;
        }
        return 0;
    }
}
