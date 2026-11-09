package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;

/** LH 공고목록·상세로 정한 공고 값. 당첨자 발표일이 없는 공고는 null이며 기존 공고 값을 그대로 둔다. */
public record LhAnnouncementValues(
        String name,
        RentalType supplyType,
        RecruitmentType recruitmentType,
        LocalDate postedDate,
        LocalDate applicationStartDate,
        LocalDate applicationEndDate,
        LocalDate winnerAnnouncementDate,
        String originalUrl
) {
}
