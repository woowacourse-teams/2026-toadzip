package com.toadzip.backend.ingest.dto;

import java.math.BigDecimal;

import com.toadzip.backend.ingest.domain.MyHomeComplexSourceData;

public record MyHomeComplexSourceItem(
        Long hsmpSn,
        String insttNm,
        String brtcCode,
        String brtcNm,
        String signguCode,
        String signguNm,
        String hsmpNm,
        String rnAdres,
        String pnu,
        String competDe,
        Integer hshldCo,
        String suplyTyNm,
        String styleNm,
        BigDecimal suplyPrvuseAr,
        BigDecimal suplyCmnuseAr,
        String houseTyNm,
        String heatMthdDetailNm,
        String buldStleNm,
        String elvtrInstlAtNm,
        Integer parkngCo,
        Long bassRentGtn,
        Long bassMtRntchrg,
        Long bassCnvrsGtnLmt
) {

    public MyHomeComplexSourceData toSourceData() {
        return new MyHomeComplexSourceData(
                hsmpSn, insttNm, brtcCode, brtcNm, signguCode, signguNm, hsmpNm, rnAdres, pnu,
                competDe, hshldCo, suplyTyNm, styleNm, suplyPrvuseAr, suplyCmnuseAr, houseTyNm,
                heatMthdDetailNm, buldStleNm, elvtrInstlAtNm, parkngCo, bassRentGtn,
                bassMtRntchrg, bassCnvrsGtnLmt
        );
    }
}
