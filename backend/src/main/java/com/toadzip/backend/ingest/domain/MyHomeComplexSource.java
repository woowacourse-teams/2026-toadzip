package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_complex_source")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String sourceKey;

    private Instant collectedAt;

    private Long hsmpSn;
    private String insttNm;
    private String brtcCode;
    private String brtcNm;
    private String signguCode;
    private String signguNm;
    private String hsmpNm;
    private String rnAdres;
    private String pnu;
    private String competDe;
    private Integer hshldCo;
    private String suplyTyNm;
    private String styleNm;

    @Column(precision = 10, scale = 4)
    private BigDecimal suplyPrvuseAr;

    @Column(precision = 10, scale = 4)
    private BigDecimal suplyCmnuseAr;

    private String houseTyNm;
    private String heatMthdDetailNm;
    private String buldStleNm;
    private String elvtrInstlAtNm;
    private Integer parkngCo;
    private Long bassRentGtn;
    private Long bassMtRntchrg;
    private Long bassCnvrsGtnLmt;

    private MyHomeComplexSource(MyHomeComplexSourceData data) {
        sourceKey = sourceKeyOf(data);
        replaceWith(data);
    }

    public static MyHomeComplexSource from(MyHomeComplexSourceData data) {
        return new MyHomeComplexSource(data);
    }

    public static String sourceKeyOf(MyHomeComplexSourceData data) {
        return keyPart(data.hsmpSn())
                + keyPart(data.pnu())
                + keyPart(data.suplyTyNm())
                + keyPart(data.styleNm())
                + keyPart(data.suplyPrvuseAr())
                + keyPart(data.suplyCmnuseAr());
    }

    public void replaceWith(MyHomeComplexSourceData data) {
        hsmpSn = data.hsmpSn();
        insttNm = trim(data.insttNm());
        brtcCode = trim(data.brtcCode());
        brtcNm = trim(data.brtcNm());
        signguCode = trim(data.signguCode());
        signguNm = trim(data.signguNm());
        hsmpNm = trim(data.hsmpNm());
        rnAdres = trim(data.rnAdres());
        pnu = trim(data.pnu());
        competDe = trim(data.competDe());
        hshldCo = data.hshldCo();
        suplyTyNm = trim(data.suplyTyNm());
        styleNm = trim(data.styleNm());
        suplyPrvuseAr = area(data.suplyPrvuseAr());
        suplyCmnuseAr = area(data.suplyCmnuseAr());
        houseTyNm = trim(data.houseTyNm());
        heatMthdDetailNm = trim(data.heatMthdDetailNm());
        buldStleNm = trim(data.buldStleNm());
        elvtrInstlAtNm = trim(data.elvtrInstlAtNm());
        parkngCo = data.parkngCo();
        bassRentGtn = data.bassRentGtn();
        bassMtRntchrg = data.bassMtRntchrg();
        bassCnvrsGtnLmt = data.bassCnvrsGtnLmt();
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    private static String keyPart(Object raw) {
        if (raw == null) {
            return "-1:";
        }
        String value = raw.toString().strip();
        if (raw instanceof BigDecimal decimal) {
            value = area(decimal).stripTrailingZeros().toPlainString();
        }
        return value.length() + ":" + value;
    }

    private static BigDecimal area(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
