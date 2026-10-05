package com.toadzip.backend.ingest.collection.myhome.complex.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_complex_source_rows", uniqueConstraints = @UniqueConstraint(
        columnNames = {"source_id", "source_order"}
))
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexSourceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private MyHomeComplexSource source;

    @Column(nullable = false)
    private int sourceOrder;

    private Instant collectedAt;

    @Column(nullable = false)
    private Long hsmpSn;

    @Column(columnDefinition = "text")
    private String insttNm;

    @Column(columnDefinition = "text")
    private String brtcCode;

    @Column(columnDefinition = "text")
    private String brtcNm;

    @Column(columnDefinition = "text")
    private String signguCode;

    @Column(columnDefinition = "text")
    private String signguNm;

    @Column(columnDefinition = "text")
    private String hsmpNm;

    @Column(columnDefinition = "text")
    private String rnAdres;

    @Column(columnDefinition = "text")
    private String pnu;

    @Column(columnDefinition = "text")
    private String competDe;

    private Integer hshldCo;

    @Column(columnDefinition = "text")
    private String suplyTyNm;

    @Column(columnDefinition = "text")
    private String styleNm;

    @Column(columnDefinition = "numeric")
    private BigDecimal suplyPrvuseAr;

    @Column(columnDefinition = "numeric")
    private BigDecimal suplyCmnuseAr;

    @Column(columnDefinition = "text")
    private String houseTyNm;

    @Column(columnDefinition = "text")
    private String heatMthdDetailNm;

    @Column(columnDefinition = "text")
    private String buldStleNm;

    @Column(columnDefinition = "text")
    private String elvtrInstlAtNm;

    private Integer parkngCo;

    private Long bassRentGtn;

    private Long bassMtRntchrg;

    private Long bassCnvrsGtnLmt;

    private MyHomeComplexSourceRow(
            MyHomeComplexSource source, int sourceOrder, MyHomeComplexSourceSnapshot snapshot
    ) {
        this.source = source;
        this.sourceOrder = sourceOrder;
        collectedAt = source.getRegion().getCollectedAt();
        hsmpSn = snapshot.hsmpSn();
        insttNm = snapshot.insttNm();
        brtcCode = snapshot.brtcCode();
        brtcNm = snapshot.brtcNm();
        signguCode = snapshot.signguCode();
        signguNm = snapshot.signguNm();
        hsmpNm = snapshot.hsmpNm();
        rnAdres = snapshot.rnAdres();
        pnu = snapshot.pnu();
        competDe = snapshot.competDe();
        hshldCo = snapshot.hshldCo();
        suplyTyNm = snapshot.suplyTyNm();
        styleNm = snapshot.styleNm();
        suplyPrvuseAr = snapshot.suplyPrvuseAr();
        suplyCmnuseAr = snapshot.suplyCmnuseAr();
        houseTyNm = snapshot.houseTyNm();
        heatMthdDetailNm = snapshot.heatMthdDetailNm();
        buldStleNm = snapshot.buldStleNm();
        elvtrInstlAtNm = snapshot.elvtrInstlAtNm();
        parkngCo = snapshot.parkngCo();
        bassRentGtn = snapshot.bassRentGtn();
        bassMtRntchrg = snapshot.bassMtRntchrg();
        bassCnvrsGtnLmt = snapshot.bassCnvrsGtnLmt();
    }

    public static MyHomeComplexSourceRow from(
            MyHomeComplexSource source, int sourceOrder, MyHomeComplexSourceSnapshot snapshot
    ) {
        if (!source.getHsmpSn().equals(snapshot.hsmpSn())
                || !source.getRegion().matches(snapshot.brtcCode(), snapshot.signguCode())) {
            throw new IllegalArgumentException("응답 행의 단지 식별자·지역이 소속 원천과 다릅니다.");
        }
        return new MyHomeComplexSourceRow(source, sourceOrder, snapshot);
    }

    public MyHomeComplexSourceSnapshot snapshot() {
        return new MyHomeComplexSourceSnapshot(
                hsmpSn, insttNm, brtcCode, brtcNm, signguCode, signguNm, hsmpNm, rnAdres, pnu,
                competDe, hshldCo, suplyTyNm, styleNm, suplyPrvuseAr, suplyCmnuseAr, houseTyNm,
                heatMthdDetailNm, buldStleNm, elvtrInstlAtNm, parkngCo, bassRentGtn, bassMtRntchrg, bassCnvrsGtnLmt
        );
    }
}
