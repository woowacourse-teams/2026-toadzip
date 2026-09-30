package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.AttachmentType;

class LhAttachmentTypePolicy {

    AttachmentType classify(String kind) {
        if (kind != null && kind.contains("취소")) {
            return AttachmentType.CANCELLATION;
        }
        if (kind != null && kind.contains("정정")) {
            return AttachmentType.CORRECTION;
        }
        if (kind != null && kind.contains("공고")) {
            return AttachmentType.ANNOUNCEMENT;
        }
        return AttachmentType.REFERENCE;
    }
}
