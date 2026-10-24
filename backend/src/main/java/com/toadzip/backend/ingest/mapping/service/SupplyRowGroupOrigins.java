package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공통 모집 묶음은 원천행 식별자로 찾는다. 마이홈 원천 한 행은 식별자가 원천 키와 같고, LH 공급정보로 여러 행이 되면
 * 첫 행은 원천 키를, 나머지는 "{공고}:LH:{LH 공고}:{순번}"을 쓰므로 기존 묶음의 키는 그대로 유지된다.
 * 여러 행으로 펼친 마이홈 원천은 행마다 따로 묶어야 하므로 공급 수와 원천 지문도 그 LH 행 기준으로 계산한다.
 */
final class SupplyRowGroupOrigins {
    private SupplyRowGroupOrigins() {
    }

    /** LH 공급정보로 두 행 이상 펼친 마이홈 원천 키. */
    static Set<String> expanded(Collection<MyHomeSupplyRowMappingData> rows) {
        return rows.stream().collect(Collectors.groupingBy(row -> row.source().getSourceKey(), Collectors.counting()))
                .entrySet().stream().filter(entry -> entry.getValue() > 1).map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    static Function<MyHomeSupplyRowMappingData, Boolean> expandedIn(Collection<MyHomeSupplyRowMappingData> rows) {
        var keys = expanded(rows);
        return row -> keys.contains(row.source().getSourceKey());
    }

    static String key(MyHomeSupplyRowMappingData row) {
        return row.sourceSupplyRowIdentifier();
    }

    static Integer householdCount(MyHomeSupplyRowMappingData row, boolean expanded) {
        return expanded ? row.totalSupplyHouseholdCount() : row.source().getSumSuplyCo();
    }

    static String housingTypeName(MyHomeSupplyRowMappingData row, boolean expanded) {
        return expanded ? row.sourceHousingTypeName() : row.source().getHouseTyNm();
    }
}
