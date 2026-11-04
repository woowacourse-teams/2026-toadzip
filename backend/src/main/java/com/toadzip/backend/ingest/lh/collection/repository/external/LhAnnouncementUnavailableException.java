package com.toadzip.backend.ingest.lh.collection.repository.external;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestRejectedException;

/** LH 공고 API 장애 차단기가 요청을 거절했다. */
public class LhAnnouncementUnavailableException extends ExternalDataRequestRejectedException {

    public LhAnnouncementUnavailableException(String message) {
        this(message, false);
    }

    public LhAnnouncementUnavailableException(String message, boolean rateLimited) {
        super(message, rateLimited);
    }
}
