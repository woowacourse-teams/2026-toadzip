package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementHousingComplex;

/** 네이티브 SQL에서 공고↔단지 연결({@link AnnouncementHousingComplex#LINKS})을 쓰는 조각. */
public final class AnnouncementComplexLinkSql {

    /** FROM·JOIN에 쓰는 연결 집합. 열은 announcement_id, housing_complex_id다. */
    public static final String LINKS = "(" + AnnouncementHousingComplex.LINKS + ")";

    private AnnouncementComplexLinkSql() {
    }

    /** 공고(announcementId 식)가 단지(complexId 식)에 이어져 있다. */
    public static String linked(String announcementId, String complexId) {
        return "EXISTS (SELECT 1 FROM " + LINKS + " announcement_link WHERE announcement_link.announcement_id = "
                + announcementId + " AND announcement_link.housing_complex_id = " + complexId + ")";
    }
}
