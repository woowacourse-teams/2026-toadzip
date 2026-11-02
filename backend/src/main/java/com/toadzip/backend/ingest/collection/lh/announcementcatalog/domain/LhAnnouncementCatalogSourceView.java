package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

import java.time.Instant;
import lombok.Getter;

/** 후보 선택이 읽는 LH 공고 목록 원천 하나. 저장된 목록 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhAnnouncementCatalogSourceView {

    private final Long id;

    private final String sourceKey;

    private final String panId;

    private final String connectionSystemDivisionCode;

    private final String upperAnnouncementTypeCode;

    private final String announcementTypeCode;

    private final String supplyInfoTypeCode;

    private final Instant changedAt;

    private final Instant collectedAt;

    private final boolean presentInLatestCatalog;

    private LhAnnouncementCatalogSourceView(
            Long id, Instant changedAt, Instant collectedAt, boolean present, LhAnnouncementCatalogSnapshot snapshot
    ) {
        LhAnnouncementCatalogSnapshot normalized = snapshot.normalized();
        this.id = id;
        sourceKey = normalized.sourceKey();
        panId = normalized.panId();
        connectionSystemDivisionCode = normalized.connectionSystemDivisionCode();
        upperAnnouncementTypeCode = normalized.upperAnnouncementTypeCode();
        announcementTypeCode = normalized.announcementTypeCode();
        supplyInfoTypeCode = normalized.supplyInfoTypeCode();
        this.changedAt = changedAt;
        this.collectedAt = collectedAt;
        presentInLatestCatalog = present;
    }

    public static LhAnnouncementCatalogSourceView of(LhAnnouncementCatalogEntry row) {
        return read(row.getId(), row.getChangedAt(), row.getCollectedAt(), row.isPresentInLatestCatalog(),
                row.snapshot());
    }

    public static LhAnnouncementCatalogSourceView read(
            Long id, Instant changedAt, Instant collectedAt, boolean present, LhAnnouncementCatalogSnapshot snapshot
    ) {
        return new LhAnnouncementCatalogSourceView(id, changedAt, collectedAt, present, snapshot);
    }
}
