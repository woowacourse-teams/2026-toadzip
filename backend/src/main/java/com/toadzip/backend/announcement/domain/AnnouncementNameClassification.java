package com.toadzip.backend.announcement.domain;

/** 공식 공고명으로 모집유형과 공급구분을 정한다. 마이홈과 LH 공고목록은 같은 공고명을 쓴다. */
public final class AnnouncementNameClassification {

    private AnnouncementNameClassification() {
    }

    public static RecruitmentType recruitmentType(String name) {
        if (name.contains("예비")) {
            return RecruitmentType.WAITLIST;
        }
        if (name.contains("모집")) {
            return RecruitmentType.NEW;
        }
        return RecruitmentType.ETC;
    }

    public static SupplyCategory supplyCategory(String name) {
        if (name.contains("예비") || name.contains("추가") || name.contains("재공급")) {
            return SupplyCategory.RESUPPLY;
        }
        return SupplyCategory.NEW_SUPPLY;
    }
}
