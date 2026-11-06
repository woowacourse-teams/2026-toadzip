package com.toadzip.backend.announcement.domain;

import java.util.List;

/**
 * 작업을 넣을 때 Python 워커에 함께 주는 입력 스냅숏(ai_jobs.input). 워커는 도메인 테이블을 읽지 않는다.
 * 형식은 ai-worker/contract/announcement-pdf-extraction/input.example.json과 같아야 한다.
 *
 * @param sourceSupplyRows 이 공고의 기관 공급 원천 행(LH 공급 API 행이나 GH 공급행 틀). 공급행 틀로 쓴다. 원천이 없는 공고(SH 등)거나
 *                         수집 전이면 비어 있다.
 * @param lhSupplyRows     sourceSupplyRows의 LH 행을 옛 형식으로 한 번 더 담는다. 이 릴리스에서 아직 바뀌지 않은 옛 워커가 LH 틀을
 *                         잃지 않게 하려는 것이고, 새 워커는 읽지 않는다. 다음 릴리스에서 지운다.
 */
public record PdfExtractionInput(List<SourceSupplyRow> sourceSupplyRows, List<LegacyLhSupplyRow> lhSupplyRows) {

    public PdfExtractionInput(List<SourceSupplyRow> sourceSupplyRows) {
        this(sourceSupplyRows, sourceSupplyRows.stream().filter(row -> "LH".equals(row.source()))
                .map(LegacyLhSupplyRow::of).toList());
    }

    /**
     * 원천 공급 한 행. 값은 문자열이고 모르면 빈 문자열이다. source는 LH·GH, order는 원천 순번(GH는 틀 행 표시 순서),
     * rowIdentifier는 공급 보정 작업실이 그 행을 찾는 식별자다. LH는 lhSupplyRows[].rowIdentifier와 같은 LH:{panId}:SUPPLY:{order},
     * GH는 rowSources의 GH_ANNOUNCEMENT 원천 키(틀 행 키)다. 워커는 공급행이 나온 원천 행을 이 식별자(sourceRow)로 돌려준다.
     */
    public record SourceSupplyRow(String source, int order, String rowIdentifier, String complexLabel, String typeName,
            String exclusiveArea, String supplyArea, String suppliedUnitCount, String totalUnitCount) {
    }

    /** 이름을 바꾸기 전의 LH 행 형식(source 없음). 다음 릴리스에서 지운다. */
    public record LegacyLhSupplyRow(int order, String rowIdentifier, String complexLabel, String typeName,
            String exclusiveArea, String supplyArea, String suppliedUnitCount, String totalUnitCount) {

        static LegacyLhSupplyRow of(SourceSupplyRow row) {
            return new LegacyLhSupplyRow(row.order(), row.rowIdentifier(), row.complexLabel(), row.typeName(),
                    row.exclusiveArea(), row.supplyArea(), row.suppliedUnitCount(), row.totalUnitCount());
        }
    }
}
