package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 근거와 함께 확인한 LH 공급 원본 행과 마이홈 원천행의 연결이다. 공고별로 LH 행마다 하나만 둔다.
 * 저장 당시 양쪽 원천의 지문이 지금과 다르면 정제와 LH 보강은 이 연결을 쓰지 않고 재확인을 요구한다.
 */
@Entity
@Getter
@Table(name = "lh_supply_row_links")
@NoArgsConstructor(access = PROTECTED)
public class LhSupplyRowLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, length = 100)
    private String sourceAnnouncementIdentifier;

    @Column(nullable = false, length = 200)
    private String lhSupplyRowIdentifier;

    @Column(nullable = false, length = 500)
    private String myhomeSourceRowIdentifier;

    @Column(nullable = false, length = 64)
    private String lhSourceFingerprint;

    @Column(nullable = false, length = 64)
    private String myhomeSourceFingerprint;

    @Column(nullable = false, length = 2048)
    private String sourceUrl;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant updatedAt;

    public LhSupplyRowLink(String sourceAnnouncementIdentifier, String lhSupplyRowIdentifier) {
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.lhSupplyRowIdentifier = lhSupplyRowIdentifier;
    }

    public void confirm(String myhomeSourceRowIdentifier, String lhSourceFingerprint, String myhomeSourceFingerprint,
            String sourceUrl, String reason, String actor) {
        this.myhomeSourceRowIdentifier = myhomeSourceRowIdentifier;
        this.lhSourceFingerprint = lhSourceFingerprint;
        this.myhomeSourceFingerprint = myhomeSourceFingerprint;
        this.sourceUrl = sourceUrl;
        this.reason = reason;
        this.actor = actor;
        this.updatedAt = Instant.now();
    }
}
