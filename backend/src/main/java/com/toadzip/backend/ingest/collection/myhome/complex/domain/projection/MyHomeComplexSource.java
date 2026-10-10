package com.toadzip.backend.ingest.collection.myhome.complex.domain.projection;

import static lombok.AccessLevel.PROTECTED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexSource {

    private Long id;

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

    private BigDecimal suplyPrvuseAr;

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

    public MyHomeComplexSourceSnapshot snapshot() {
        return new MyHomeComplexSourceSnapshot(
                hsmpSn, insttNm, brtcCode, brtcNm, signguCode, signguNm,
                hsmpNm, rnAdres, pnu, competDe, hshldCo, suplyTyNm, styleNm,
                suplyPrvuseAr, suplyCmnuseAr, houseTyNm, heatMthdDetailNm,
                buldStleNm, elvtrInstlAtNm, parkngCo,
                bassRentGtn, bassMtRntchrg, bassCnvrsGtnLmt
        );
    }

    public static boolean sameSourceComplex(String leftKey, String rightKey) {
        return firstKeyPart(leftKey).equals(firstKeyPart(rightKey));
    }

    private static String firstKeyPart(String key) {
        int separator = key.indexOf(':');
        int length = Integer.parseInt(key.substring(0, separator));
        if (length < 1 || separator + 1 + length > key.length()) {
            throw new IllegalArgumentException("유효하지 않은 마이홈 원천 식별자입니다.");
        }
        return key.substring(separator + 1, separator + 1 + length);
    }

    public static MyHomeComplexSource read(Long id, int order, Instant collectedAt,
            com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot snapshot) {
        var normalized = new MyHomeComplexSourceSnapshot(snapshot.hsmpSn(), snapshot.insttNm(), snapshot.brtcCode(),
                snapshot.brtcNm(), snapshot.signguCode(), snapshot.signguNm(), snapshot.hsmpNm(), snapshot.rnAdres(),
                snapshot.pnu(), snapshot.competDe(), snapshot.hshldCo(), snapshot.suplyTyNm(), snapshot.styleNm(),
                snapshot.suplyPrvuseAr(), snapshot.suplyCmnuseAr(), snapshot.houseTyNm(), snapshot.heatMthdDetailNm(),
                snapshot.buldStleNm(), snapshot.elvtrInstlAtNm(), snapshot.parkngCo(), snapshot.bassRentGtn(),
                snapshot.bassMtRntchrg(), snapshot.bassCnvrsGtnLmt());
        var source = from(normalized);
        source.id = id;
        source.collectedAt = collectedAt;
        return source;
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
