package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

public record LhAnnouncementCatalogRow(LhAnnouncementCatalogSnapshot snapshot, String rawPayload) {

    public LhAnnouncementCatalogRow {
        if (snapshot == null || rawPayload == null || rawPayload.isBlank()) {
            throw new IllegalArgumentException("LH 공고 목록 응답 행이 비어 있습니다.");
        }
        snapshot.validateIdentifiers();
    }
}
