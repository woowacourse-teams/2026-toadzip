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

import com.toadzip.backend.ingest.dto.MyHomeComplexSourceItem;

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

    private MyHomeComplexSource(MyHomeComplexSourceItem item) {
        sourceKey = sourceKeyOf(item);
        replaceWith(item);
    }

    public static MyHomeComplexSource from(MyHomeComplexSourceItem item) {
        return new MyHomeComplexSource(item);
    }

    public static String sourceKeyOf(MyHomeComplexSourceItem item) {
        return keyPart(item.hsmpSn())
                + keyPart(item.pnu())
                + keyPart(item.suplyTyNm())
                + keyPart(item.styleNm())
                + keyPart(item.suplyPrvuseAr())
                + keyPart(item.suplyCmnuseAr());
    }

    public void replaceWith(MyHomeComplexSourceItem item) {
        hsmpSn = item.hsmpSn();
        insttNm = trim(item.insttNm());
        brtcCode = trim(item.brtcCode());
        brtcNm = trim(item.brtcNm());
        signguCode = trim(item.signguCode());
        signguNm = trim(item.signguNm());
        hsmpNm = trim(item.hsmpNm());
        rnAdres = trim(item.rnAdres());
        pnu = trim(item.pnu());
        competDe = trim(item.competDe());
        hshldCo = item.hshldCo();
        suplyTyNm = trim(item.suplyTyNm());
        styleNm = trim(item.styleNm());
        suplyPrvuseAr = area(item.suplyPrvuseAr());
        suplyCmnuseAr = area(item.suplyCmnuseAr());
        houseTyNm = trim(item.houseTyNm());
        heatMthdDetailNm = trim(item.heatMthdDetailNm());
        buldStleNm = trim(item.buldStleNm());
        elvtrInstlAtNm = trim(item.elvtrInstlAtNm());
        parkngCo = item.parkngCo();
        bassRentGtn = item.bassRentGtn();
        bassMtRntchrg = item.bassMtRntchrg();
        bassCnvrsGtnLmt = item.bassCnvrsGtnLmt();
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
