package com.toadzip.backend.ingest.lh.refinement.service;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;

/** LH 보강을 할 수 없을 때 사유와 함께 던진다. 공고 한 건의 정제·보강 저장을 되돌리는 신호다. */
public class LhAnnouncementEnrichmentRejectedException extends RuntimeException {

    private final LhAnnouncementEnrichmentFailureReason reason;

    LhAnnouncementEnrichmentRejectedException(LhAnnouncementEnrichmentFailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public LhAnnouncementEnrichmentFailureReason reason() {
        return reason;
    }
}
