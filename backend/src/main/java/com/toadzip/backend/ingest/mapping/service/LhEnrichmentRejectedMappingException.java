package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentRejectedException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;

/** 전체 정제와 단건 정제가 같은 실패로 기록할 수 있도록 LH 보강 거절을 다른 정제 거절과 구분한다. */
class LhEnrichmentRejectedMappingException extends MyHomeAnnouncementMappingRejectedException {

    LhEnrichmentRejectedMappingException(LhAnnouncementEnrichmentRejectedException cause) {
        super(MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                "LH 보강 실패: " + cause.reason() + " - " + cause.getMessage());
    }
}
