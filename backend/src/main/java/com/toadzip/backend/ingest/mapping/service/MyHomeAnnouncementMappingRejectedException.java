package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;

class MyHomeAnnouncementMappingRejectedException extends RuntimeException {

    private final MyHomeAnnouncementMappingFailureReason reason;

    MyHomeAnnouncementMappingRejectedException(
            MyHomeAnnouncementMappingFailureReason reason,
            String detail
    ) {
        super(detail);
        this.reason = reason;
    }

    MyHomeAnnouncementMappingFailureReason reason() {
        return reason;
    }
}
