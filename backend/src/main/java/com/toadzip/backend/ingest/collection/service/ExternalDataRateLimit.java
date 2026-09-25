package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;

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
        if (exception instanceof LhAnnouncementUnavailableException failure
                && failure.isRateLimited()) {
            return 1;
        }
        return 0;
    }
}
