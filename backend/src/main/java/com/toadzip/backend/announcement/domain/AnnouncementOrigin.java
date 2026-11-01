package com.toadzip.backend.announcement.domain;

/**
 * 공고 값의 출처. LH 공고 ID가 붙은 공고는 LH 공고 정제가 이어받을 LH 원천 공고다. 관리자 직접 등록 공고는 정제가 건드리지 않는다.
 * 나머지는 마이홈 공고 정제가 만든 과거 공고다. 저장하지 않고 원천 식별자와 LH 공고 ID로 정한다.
 */
public enum AnnouncementOrigin {
    LH_SOURCE,
    ADMIN_ENTRY,
    MYHOME_LEGACY;

    /** 관리자 등록 공고의 원천 식별자 접두사. 공급정보 소유 마이그레이션의 기존 공고 채우기와 같은 규칙이다. */
    private static final String ADMIN_ENTRY_PREFIX = "ADMIN_ENTRY-";

    static AnnouncementOrigin of(String sourceAnnouncementIdentifier, String lhPanId) {
        if (lhPanId != null) {
            return LH_SOURCE;
        }
        if (sourceAnnouncementIdentifier.startsWith(ADMIN_ENTRY_PREFIX)) {
            return ADMIN_ENTRY;
        }
        return MYHOME_LEGACY;
    }
}
