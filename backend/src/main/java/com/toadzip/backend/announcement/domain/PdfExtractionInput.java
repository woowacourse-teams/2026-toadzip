package com.toadzip.backend.announcement.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import java.util.List;

/**
 * 작업을 넣을 때 Python 워커에 함께 주는 입력 스냅숏(ai_jobs.input). 워커는 도메인 테이블을 읽지 않는다.
 * 형식은 ai-worker/contract/announcement-pdf-extraction/input.example.json과 같아야 한다.
 *
 * @param sourceSupplyRows 이 공고의 기관 공급 원천 행(LH 공급 API 행이나 GH 공급행 틀). 공급행 틀로 쓴다. 원천이 없는 공고(SH 등)거나
 *                         수집 전이면 비어 있다.
 * @param rentalType       작업을 넣을 때 공고의 임대유형. 워커가 형명 끝 신청자 표기('29 (주거약자)')를 공급대상으로 나눌 때 표기 없는 줄을
 *                         '일반'(국민임대·영구임대)으로 부를지 정한다. 이 필드가 생기기 전에 넣은 작업에는 없다.
 */
public record PdfExtractionInput(List<SourceSupplyRow> sourceSupplyRows,
        @JsonInclude(Include.NON_NULL) RentalType rentalType,
        @JsonInclude(Include.NON_NULL) Publication publication) {
    public PdfExtractionInput(List<SourceSupplyRow> sourceSupplyRows) {
        this(sourceSupplyRows, null, null);
    }

    public enum PublicationMode { OFF, SHADOW, APPLY }

    /**
     * 자동 공개 판정에 쓰는 공고 스냅숏. rentalType부터 winnerAnnouncementDate까지는 작업을 넣을 때 공고가 가진 수집값이고 없으면 null이다.
     * 워커는 PDF에서 읽은 날짜가 수집값과 다르면 보류하고, 수집값이 있는 유형·날짜는 원문 대조 모델에 묻지 않는다.
     */
    public record Publication(long attachmentId, long announcementVersion, String title, PublicationMode mode,
            RentalType rentalType, RecruitmentType recruitmentType, LocalDate applicationStartDate,
            LocalDate applicationEndDate, LocalDate winnerAnnouncementDate) { }

    /**
     * 원천 공급 한 행. 값은 문자열이고 모르면 빈 문자열이다. source는 LH·GH, order는 원천 순번(GH는 틀 행 표시 순서),
     * rowIdentifier는 공급 보정 작업실이 그 행을 찾는 식별자다. LH는 작업실 lhSupplyRows[].rowIdentifier와 같은 LH:{panId}:SUPPLY:{order},
     * GH는 rowSources의 GH_ANNOUNCEMENT 원천 키(틀 행 키)다. 워커는 공급행이 나온 원천 행을 이 식별자(sourceRow)로 돌려준다.
     */
    public record SourceSupplyRow(String source, int order, String rowIdentifier, String complexLabel, String typeName,
            String exclusiveArea, String supplyArea, String suppliedUnitCount, String totalUnitCount) {
    }
}
