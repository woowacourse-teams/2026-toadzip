package com.toadzip.backend.ingest.collection.myhome.complex.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_complex_source_regions", uniqueConstraints = @UniqueConstraint(
        columnNames = {"province_code", "district_code"}
))
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexRegionSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, length = 2)
    private String provinceCode;

    @Column(nullable = false, length = 3)
    private String districtCode;

    private Instant collectedAt;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "last_collection_record_id", nullable = false)
    private SourceCollectionRecord lastCollectionRecord;

    private MyHomeComplexRegionSource(String provinceCode, String districtCode) {
        this.provinceCode = provinceCode;
        this.districtCode = districtCode;
    }

    public static MyHomeComplexRegionSource create(String provinceCode, String districtCode) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2}")
                || districtCode == null || !districtCode.matches("[0-9]{3}")) {
            throw new IllegalArgumentException("시도 두 자리·시군구 세 자리 코드는 함께 입력해야 합니다.");
        }
        return new MyHomeComplexRegionSource(provinceCode, districtCode);
    }

    public void replaceSnapshot(Instant collectedAt, SourceCollectionRecord record) {
        if (collectedAt == null || this.collectedAt != null && this.collectedAt.isAfter(collectedAt)) {
            throw new IllegalArgumentException("이미 저장된 지역 원천보다 오래된 수집 응답은 반영할 수 없습니다.");
        }
        if (record.getSource() != CollectionSource.MYHOME_COMPLEX || record.getStatus() == CollectionStatus.FAILED
                || !provinceCode.equals(record.getRequestParameters().get("brtcCode"))
                || !districtCode.equals(record.getRequestParameters().get("signguCode"))) {
            throw new IllegalArgumentException("지역 원천의 조회 조건과 수집 기록이 일치하지 않습니다.");
        }
        this.collectedAt = collectedAt;
        lastCollectionRecord = record;
    }

    public boolean matches(String provinceCode, String districtCode) {
        return this.provinceCode.equals(provinceCode) && this.districtCode.equals(districtCode);
    }
}
