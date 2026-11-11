package com.toadzip.backend.announcement.domain;

/**
 * 공고가 공고 목록의 어느 탭에 보이는지. 공고 상세 주소로 들어온 화면이 그 탭을 펼칠 때 쓴다.
 * 판정은 목록 조회와 같은 조건을 쓴다(모집 중: 공고중·접수중, 접수마감: 마지막 일정 후 7일까지).
 */
public enum AnnouncementListPhase {
    RECRUITING,
    CLOSED,
    NONE
}
