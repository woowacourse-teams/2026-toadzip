package com.toadzip.backend.ingest.collection.myhome.complex.domain.projection;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceRow;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.domain.SourceText;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/** 정제가 읽는 마이홈 단지 원천 행 하나. 원천 행 Entity를 읽어 만든 뒤 바뀌지 않는다. */
@Getter
public class MyHomeComplexSourceView {

    private final Long id;

    private final String sourceKey;

    private final Instant collectedAt;

    private final Long hsmpSn;
    private final String insttNm;
    private final String brtcCode;
    private final String brtcNm;
    private final String signguCode;
    private final String signguNm;
    private final String hsmpNm;
    private final String rnAdres;
    private final String pnu;
    private final String competDe;
    private final Integer hshldCo;
    private final String suplyTyNm;
    private final String styleNm;

    private final BigDecimal suplyPrvuseAr;

    private final BigDecimal suplyCmnuseAr;

    private final String houseTyNm;
    private final String heatMthdDetailNm;
    private final String buldStleNm;
    private final String elvtrInstlAtNm;
    private final Integer parkngCo;
    private final Long bassRentGtn;
    private final Long bassMtRntchrg;
    private final Long bassCnvrsGtnLmt;

    private MyHomeComplexSourceView(Long id, Instant collectedAt, MyHomeComplexSourceSnapshot snapshot) {
        this.id = id;
        this.collectedAt = collectedAt;
        sourceKey = sourceKeyOf(snapshot);
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

    public static MyHomeComplexSourceView of(MyHomeComplexSourceRow row) {
        return read(row.getId(), row.getCollectedAt(), row.snapshot());
    }

    public static MyHomeComplexSourceView read(Long id, Instant collectedAt, MyHomeComplexSourceSnapshot snapshot) {
        return new MyHomeComplexSourceView(id, collectedAt, snapshot);
    }

    public static String sourceKeyOf(MyHomeComplexSourceSnapshot snapshot) {
        return keyPart(snapshot.hsmpSn())
                + keyPart(snapshot.pnu())
                + keyPart(snapshot.suplyTyNm())
                + keyPart(snapshot.styleNm())
                + keyPart(snapshot.suplyPrvuseAr())
                + keyPart(snapshot.suplyCmnuseAr());
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
