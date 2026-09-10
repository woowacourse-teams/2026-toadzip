package com.toadzip.backend.ingest.collection.domain;

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

    private MyHomeComplexSource(MyHomeComplexSourceSnapshot snapshot) {
        sourceKey = sourceKeyOf(snapshot);
        replaceWith(snapshot);
    }

    public static MyHomeComplexSource from(MyHomeComplexSourceSnapshot snapshot) {
        return new MyHomeComplexSource(snapshot);
    }

    public static String sourceKeyOf(MyHomeComplexSourceSnapshot snapshot) {
        return keyPart(snapshot.hsmpSn())
                + keyPart(snapshot.pnu())
                + keyPart(snapshot.suplyTyNm())
                + keyPart(snapshot.styleNm())
                + keyPart(snapshot.suplyPrvuseAr())
                + keyPart(snapshot.suplyCmnuseAr());
    }

    public void replaceWith(MyHomeComplexSourceSnapshot snapshot) {
        hsmpSn = snapshot.hsmpSn();
        insttNm = trim(snapshot.insttNm());
        brtcCode = trim(snapshot.brtcCode());
        brtcNm = trim(snapshot.brtcNm());
        signguCode = trim(snapshot.signguCode());
        signguNm = trim(snapshot.signguNm());
        hsmpNm = trim(snapshot.hsmpNm());
        rnAdres = trim(snapshot.rnAdres());
        pnu = trim(snapshot.pnu());
        competDe = trim(snapshot.competDe());
        hshldCo = snapshot.hshldCo();
        suplyTyNm = trim(snapshot.suplyTyNm());
        styleNm = trim(snapshot.styleNm());
        suplyPrvuseAr = area(snapshot.suplyPrvuseAr());
        suplyCmnuseAr = area(snapshot.suplyCmnuseAr());
        houseTyNm = trim(snapshot.houseTyNm());
        heatMthdDetailNm = trim(snapshot.heatMthdDetailNm());
        buldStleNm = trim(snapshot.buldStleNm());
        elvtrInstlAtNm = trim(snapshot.elvtrInstlAtNm());
        parkngCo = snapshot.parkngCo();
        bassRentGtn = snapshot.bassRentGtn();
        bassMtRntchrg = snapshot.bassMtRntchrg();
        bassCnvrsGtnLmt = snapshot.bassCnvrsGtnLmt();
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
