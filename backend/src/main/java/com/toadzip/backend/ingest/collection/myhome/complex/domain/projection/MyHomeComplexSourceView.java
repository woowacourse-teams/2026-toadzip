package com.toadzip.backend.ingest.collection.myhome.complex.domain.projection;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.domain.SourceText;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexSourceView {

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

    private MyHomeComplexSourceView(MyHomeComplexSourceSnapshot snapshot) {
        sourceKey = sourceKeyOf(snapshot);
        replaceWith(snapshot);
    }

    public static MyHomeComplexSourceView from(MyHomeComplexSourceSnapshot snapshot) {
        return new MyHomeComplexSourceView(snapshot);
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
        insttNm = SourceText.strippedOrNull(snapshot.insttNm());
        brtcCode = SourceText.strippedOrNull(snapshot.brtcCode());
        brtcNm = SourceText.strippedOrNull(snapshot.brtcNm());
        signguCode = SourceText.strippedOrNull(snapshot.signguCode());
        signguNm = SourceText.strippedOrNull(snapshot.signguNm());
        hsmpNm = SourceText.strippedOrNull(snapshot.hsmpNm());
        rnAdres = SourceText.strippedOrNull(snapshot.rnAdres());
        pnu = SourceText.strippedOrNull(snapshot.pnu());
        competDe = SourceText.strippedOrNull(snapshot.competDe());
        hshldCo = snapshot.hshldCo();
        suplyTyNm = SourceText.strippedOrNull(snapshot.suplyTyNm());
        styleNm = SourceText.strippedOrNull(snapshot.styleNm());
        suplyPrvuseAr = SourceArea.normalized(snapshot.suplyPrvuseAr());
        suplyCmnuseAr = SourceArea.normalized(snapshot.suplyCmnuseAr());
        houseTyNm = SourceText.strippedOrNull(snapshot.houseTyNm());
        heatMthdDetailNm = SourceText.strippedOrNull(snapshot.heatMthdDetailNm());
        buldStleNm = SourceText.strippedOrNull(snapshot.buldStleNm());
        elvtrInstlAtNm = SourceText.strippedOrNull(snapshot.elvtrInstlAtNm());
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

    public static MyHomeComplexSourceView read(Long id, Instant collectedAt, MyHomeComplexSourceSnapshot snapshot) {
        var source = from(snapshot);
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
            value = SourceArea.normalized(decimal).stripTrailingZeros().toPlainString();
        }
        return value.length() + ":" + value;
    }
}
