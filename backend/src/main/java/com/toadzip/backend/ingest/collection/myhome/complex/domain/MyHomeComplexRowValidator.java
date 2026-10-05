package com.toadzip.backend.ingest.collection.myhome.complex.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public final class MyHomeComplexRowValidator {

    private final String provinceCode;
    private final String districtCode;
    private final Map<RowKey, MyHomeComplexSourceSnapshot> seen = new HashMap<>();

    public MyHomeComplexRowValidator(String provinceCode, String districtCode) {
        this.provinceCode = provinceCode;
        this.districtCode = districtCode;
    }

    public void validate(MyHomeComplexSourceSnapshot row) {
        if (row.hsmpSn() == null || row.hsmpSn() <= 0) {
            throw new IllegalArgumentException("마이홈 단지 식별자는 양수여야 합니다.");
        }
        if (!provinceCode.equals(row.brtcCode()) || !districtCode.equals(row.signguCode())) {
            throw new IllegalArgumentException("응답 행의 지역이 요청한 지역과 다릅니다.");
        }
        MyHomeComplexSourceSnapshot normalized = normalized(row);
        RowKey key = new RowKey(row.hsmpSn(), keyString(row.pnu()), keyString(row.suplyTyNm()),
                keyString(row.styleNm()), normalized.suplyPrvuseAr(), normalized.suplyCmnuseAr());
        MyHomeComplexSourceSnapshot previous = seen.putIfAbsent(key, normalized);
        if (previous != null && !previous.equals(normalized)) {
            throw new IllegalArgumentException("동일한 단지·주택형 식별 행의 응답 내용이 충돌합니다.");
        }
    }

    // 기존 행 식별·충돌 판정만 정규화한다. 저장에는 원래 응답을 그대로 사용한다.
    private static MyHomeComplexSourceSnapshot normalized(MyHomeComplexSourceSnapshot row) {
        return new MyHomeComplexSourceSnapshot(
                row.hsmpSn(), trim(row.insttNm()), trim(row.brtcCode()), trim(row.brtcNm()),
                trim(row.signguCode()), trim(row.signguNm()), trim(row.hsmpNm()), trim(row.rnAdres()),
                trim(row.pnu()), trim(row.competDe()), row.hshldCo(), trim(row.suplyTyNm()), trim(row.styleNm()),
                area(row.suplyPrvuseAr()), area(row.suplyCmnuseAr()), trim(row.houseTyNm()),
                trim(row.heatMthdDetailNm()), trim(row.buldStleNm()), trim(row.elvtrInstlAtNm()), row.parkngCo(),
                row.bassRentGtn(), row.bassMtRntchrg(), row.bassCnvrsGtnLmt()
        );
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String keyString(String value) {
        if (value == null) {
            return null;
        }
        return value.strip();
    }

    private static BigDecimal area(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private record RowKey(
            Long hsmpSn, String pnu, String supplyType, String style, BigDecimal privateArea, BigDecimal commonArea
    ) {
    }
}
