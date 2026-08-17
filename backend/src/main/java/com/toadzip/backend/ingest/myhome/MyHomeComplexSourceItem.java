package com.toadzip.backend.ingest.myhome;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MyHomeComplexSourceItem(Long hsmpSn, String insttNm, String brtcCode, String brtcNm, String signguCode,
		String signguNm, String hsmpNm, String rnAdres, String pnu, String competDe, Integer hshldCo, String suplyTyNm,
		String styleNm, BigDecimal suplyPrvuseAr, BigDecimal suplyCmnuseAr, String houseTyNm, String heatMthdDetailNm,
		String buldStleNm, String elvtrInstlAtNm, Integer parkngCo, Long bassRentGtn, Long bassMtRntchrg,
		Long bassCnvrsGtnLmt) {
}
