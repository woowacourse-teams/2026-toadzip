package com.toadzip.backend.announcement.domain;

import java.util.List;

/**
 * 작업을 넣을 때 Python 워커에 함께 주는 입력 스냅숏(ai_jobs.input). 워커는 도메인 테이블을 읽지 않는다.
 * 형식은 ai-worker/contract/announcement-pdf-extraction/input.example.json과 같아야 한다.
 *
 * @param lhSupplyRows 이 공고의 LH 공급 API 행. 공급행 틀로 쓴다. LH 공고가 아니거나 수집 전이면 비어 있다.
 */
public record PdfExtractionInput(List<LhSupplyRow> lhSupplyRows) {

    /** LH가 보낸 값 그대로(문자열)다. order는 원천 순번이다. */
    public record LhSupplyRow(int order, String complexLabel, String typeName, String exclusiveArea,
            String supplyArea, String suppliedUnitCount, String totalUnitCount) {
    }
}
