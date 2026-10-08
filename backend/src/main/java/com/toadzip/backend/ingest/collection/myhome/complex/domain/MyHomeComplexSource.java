package com.toadzip.backend.ingest.collection.myhome.complex.domain;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity(name = "CollectedMyHomeComplexSource")
@Table(name = "myhome_complex_source_bundles")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true)
    private Long hsmpSn;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private MyHomeComplexRegionSource region;

    @OneToMany(mappedBy = "source", cascade = ALL, orphanRemoval = true)
    @OrderBy("sourceOrder asc")
    private List<MyHomeComplexSourceRow> rows = new ArrayList<>();

    private MyHomeComplexSource(Long hsmpSn, MyHomeComplexRegionSource region) {
        this.hsmpSn = hsmpSn;
        this.region = region;
    }

    public static MyHomeComplexSource create(Long hsmpSn, MyHomeComplexRegionSource region) {
        if (hsmpSn == null || hsmpSn <= 0 || region == null) {
            throw new IllegalArgumentException("단지 식별자와 지역 원천은 필수입니다.");
        }
        return new MyHomeComplexSource(hsmpSn, region);
    }

    public void clearResponseRows() {
        rows.clear();
    }

    public void addResponseRows(List<MyHomeComplexSourceSnapshot> snapshots) {
        for (int index = 0; index < snapshots.size(); index++) {
            rows.add(MyHomeComplexSourceRow.from(this, index, snapshots.get(index)));
        }
    }

    public List<MyHomeComplexSourceRow> getRows() {
        return List.copyOf(rows);
    }
}
