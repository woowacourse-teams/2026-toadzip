package com.toadzip.backend.announcement.domain;

/**
 * LH 원천으로 만든 공급행의 매칭 상태. 앞선 상태가 먼저다. MATCHED는 대표 전용면적의 주택형까지 찾았다는 뜻이고, 함께 모집하는 면적이
 * 모두 연결됐다는 뜻은 아니다.
 */
public enum LhSupplyMatchStatus {
    /** 공고 단지가 제품 단지에 연결되지 않았다. */
    COMPLEX_UNLINKED,
    /** 연결 단지에 LH 대표 전용면적과 같은 주택형이 하나가 아니다. */
    AREA_UNMATCHED,
    /** 보증금·임대료를 LH가 주지 않아 공고문으로 채워야 한다. */
    VALUES_MISSING,
    MATCHED
}
