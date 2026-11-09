package com.toadzip.backend.announcement.domain;

/**
 * 공고를 사용자에게 보여도 되는지. 미확정 공고는 원천에서 일정·유형을 다 읽지 못해 관리자 보정을 기다리는 공고로, 관리자 화면에만 보인다.
 * 관리자가 필수값을 모두 채우면 한 번 공개되고 미확정으로 돌아가지 않는다.
 */
public enum AnnouncementReleaseStatus {
    UNCONFIRMED,
    PUBLISHED
}
