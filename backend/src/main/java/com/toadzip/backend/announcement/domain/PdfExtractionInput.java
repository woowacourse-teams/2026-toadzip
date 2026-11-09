package com.toadzip.backend.announcement.domain;

import java.util.List;

/**
 * 작업을 넣을 때 Python 워커에 함께 주는 입력 스냅숏(ai_jobs.input). 워커는 도메인 테이블을 읽지 않는다.
 * 형식은 ai-worker/contract/announcement-pdf-extraction/input.example.json과 같아야 한다.
 *
 * @param sourceSupplyRows 이 공고의 기관 공급 원천 행(LH 공급 API 행이나 GH 공급행 틀). 공급행 틀로 쓴다. 원천이 없는 공고(SH 등)거나
 *                         수집 전이면 비어 있다.
 */
public record PdfExtractionInput(List<SourceSupplyRow> sourceSupplyRows) {

    /**
     * 원천 공급 한 행. 값은 문자열이고 모르면 빈 문자열이다. source는 LH·GH, order는 원천 순번(GH는 틀 행 표시 순서),
     * rowIdentifier는 공급 보정 작업실이 그 행을 찾는 식별자다. LH는 작업실 lhSupplyRows[].rowIdentifier와 같은 LH:{panId}:SUPPLY:{order},
     * GH는 rowSources의 GH_ANNOUNCEMENT 원천 키(틀 행 키)다. 워커는 공급행이 나온 원천 행을 이 식별자(sourceRow)로 돌려준다.
     */
    public record SourceSupplyRow(String source, int order, String rowIdentifier, String complexLabel, String typeName,
            String exclusiveArea, String supplyArea, String suppliedUnitCount, String totalUnitCount) {
    }
}
