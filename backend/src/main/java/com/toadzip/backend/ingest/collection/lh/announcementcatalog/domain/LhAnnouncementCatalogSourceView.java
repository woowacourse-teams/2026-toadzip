package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;

/** 후보 선택이 읽는 LH 공고 목록 원천 하나. 저장된 목록 행을 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class LhAnnouncementCatalogSourceView {

    private static final String RENTAL_HOUSING = "06";
    /** 공급정보 조회를 지원하는 임대주택 공급유형. 어린이집 운영자 모집(064) 등은 제외한다. */
    private static final Set<String> RENTAL_SUPPLY_INFO_TYPES = Set.of("060", "061", "062", "063");
    private static final String NEWLYWED_HOPE = "39";
    /** 신혼희망타운(39) 중 임대인 행복주택(신혼희망, 42)과 통합공공임대(신혼희망, 51). 공공분양(39)은 제외한다. */
    private static final Set<String> NEWLYWED_HOPE_RENTAL_TYPES = Set.of("42", "51");
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
    }

    /** 공고목록의 공고일(`2026.09.01`). 형식이 다르거나 비어 있으면 비어 있다. */
    public Optional<LocalDate> noticeLocalDate() {
        return catalogDate(noticeDate);
    }

    public boolean isRentalHousingAnnouncement() {
        return RENTAL_HOUSING.equals(upperAnnouncementTypeCode) && RENTAL_SUPPLY_INFO_TYPES.contains(supplyInfoTypeCode)
                || NEWLYWED_HOPE.equals(upperAnnouncementTypeCode) && NEWLYWED_HOPE_RENTAL_TYPES.contains(announcementTypeCode);
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
