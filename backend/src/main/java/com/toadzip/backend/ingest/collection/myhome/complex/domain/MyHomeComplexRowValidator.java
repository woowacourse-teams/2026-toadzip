package com.toadzip.backend.ingest.collection.myhome.complex.domain;

import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.domain.SourceText;
import java.math.BigDecimal;
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
                row.hsmpSn(), SourceText.strippedOrNull(row.insttNm()), SourceText.strippedOrNull(row.brtcCode()), SourceText.strippedOrNull(row.brtcNm()),
                SourceText.strippedOrNull(row.signguCode()), SourceText.strippedOrNull(row.signguNm()), SourceText.strippedOrNull(row.hsmpNm()), SourceText.strippedOrNull(row.rnAdres()),
                SourceText.strippedOrNull(row.pnu()), SourceText.strippedOrNull(row.competDe()), row.hshldCo(), SourceText.strippedOrNull(row.suplyTyNm()), SourceText.strippedOrNull(row.styleNm()),
                SourceArea.normalized(row.suplyPrvuseAr()), SourceArea.normalized(row.suplyCmnuseAr()), SourceText.strippedOrNull(row.houseTyNm()),
                SourceText.strippedOrNull(row.heatMthdDetailNm()), SourceText.strippedOrNull(row.buldStleNm()), SourceText.strippedOrNull(row.elvtrInstlAtNm()), row.parkngCo(),
                row.bassRentGtn(), row.bassMtRntchrg(), row.bassCnvrsGtnLmt()
        );
    }

    private static String keyString(String value) {
        if (value == null) {
            return null;
        }
        return value.strip();
    }

    private record RowKey(
            Long hsmpSn, String pnu, String supplyType, String style, BigDecimal privateArea, BigDecimal commonArea
    ) {
    }
}
