package com.toadzip.backend.notice;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 공고 한 버전의 비교 가능한 내용을 묶은 값이다.
 *
 * <p>
 * 식별자와 버전 연결 정보는 포함하지 않는다.
 */
public record NoticeSnapshot(String changeStatusName, LocalDateTime publishedAt, String title, String detailUrl,
		LocalDate applicationBeginOn, LocalDate applicationEndOn, LocalDate winnerAnnouncedOn,
		String supplyInstitutionName, String houseTypeName, String supplyTypeName, String contact) {
}
