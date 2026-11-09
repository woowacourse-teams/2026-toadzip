package com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import lombok.Getter;

/** 후보 선택이 읽는 LH 공고 목록 원천 하나. 저장된 목록 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhAnnouncementCatalogSourceView {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

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

    private final String closingDate;

    private final String announcementName;

    private final String noticeDate;

    private final String detailUrl;

    private final boolean rentalHousingAnnouncement;

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
        closingDate = normalized.closingDate();
        announcementName = normalized.announcementName();
        noticeDate = normalized.noticeDate();
        detailUrl = normalized.detailUrl();
        rentalHousingAnnouncement = normalized.isRentalHousingAnnouncement();
    }

    /** 공고목록의 공고일(`2026.09.01`). 형식이 다르거나 비어 있으면 비어 있다. */
    public Optional<LocalDate> noticeLocalDate() {
        return catalogDate(noticeDate);
    }

    /** 공고목록의 접수 마감일(`2026.09.01`). 형식이 다르거나 비어 있으면 비어 있다. */
    public Optional<LocalDate> closingLocalDate() {
        return catalogDate(closingDate);
    }

    private static Optional<LocalDate> catalogDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value, CATALOG_DATE));
        }
        catch (DateTimeParseException exception) {
            return Optional.empty();
        }
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
