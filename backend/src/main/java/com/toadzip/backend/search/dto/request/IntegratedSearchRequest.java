package com.toadzip.backend.search.dto.request;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.search.domain.SearchType;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;

public record IntegratedSearchRequest(
        String query,
        @Parameter(description = "유형 미지정 검색의 미리보기 여부. 유형 지정 시 페이지 방식으로 조회합니다.") Boolean preview,
        Integer page,
        @Parameter(description = "유형별 검색은 1~20개, 기본 5개. 유형 미지정 전체 검색은 20개.") Integer size,
        List<RentalType> rentalTypes,
        List<ApplicationStatus> applicationStatuses,
        Boolean hasActiveAnnouncement,
        @Parameter(description = "지정한 유형만 조회합니다. 생략하면 기존 통합 검색을 제공합니다.") SearchType type
) {
}
