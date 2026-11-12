package com.toadzip.backend.announcement.domain;

import java.time.LocalDate;

/**
 * 접수가 끝난 공고를 접수마감 목록에 남겨 두는 기간이다.
 * 접수한 사람이 결과 발표와 입주 같은 이후 일정을 다시 확인하도록 마지막 일정 후 며칠까지 남긴다.
 * 마지막 일정은 접수 종료일, 당첨자 발표일과 공고 일정 종료일 중 가장 늦은 날이다.
 */
public final class ClosedAnnouncementFollowUp {

    public static final int DAYS_AFTER_LAST_SCHEDULE = 7;

    private ClosedAnnouncementFollowUp() {
    }

    /** 마지막 일정이 이 날짜 이후인 마감 공고만 접수마감 목록에 남는다. */
    public static LocalDate cutoff(LocalDate today) {
        return today.minusDays(DAYS_AFTER_LAST_SCHEDULE);
    }
}
