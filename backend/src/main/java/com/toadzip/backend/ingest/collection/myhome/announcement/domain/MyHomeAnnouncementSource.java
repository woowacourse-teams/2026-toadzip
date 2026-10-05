package com.toadzip.backend.ingest.collection.myhome.announcement.domain;

import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity(name = "CollectedMyHomeAnnouncementSource")
@Table(name = "myhome_announcement_source_bundles")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true, length = 100)
    private String pblancId;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "last_collection_record_id", nullable = false)
    private SourceCollectionRecord lastCollectionRecord;

    @OneToMany(mappedBy = "source", cascade = ALL, orphanRemoval = true)
    @OrderBy("requestSupplyTypeCode asc, sourceOrder asc")
    private List<MyHomeAnnouncementSourceRow> rows = new ArrayList<>();

    private MyHomeAnnouncementSource(String pblancId, SourceCollectionRecord record) {
        this.pblancId = pblancId;
        lastCollectionRecord = record;
    }

    public static MyHomeAnnouncementSource create(String pblancId, SourceCollectionRecord record) {
        if (pblancId == null || pblancId.isBlank() || pblancId.length() > 100 || record == null) {
            throw new IllegalArgumentException("원천 공고 식별자와 수집 기록은 필수입니다.");
        }
        return new MyHomeAnnouncementSource(pblancId, record);
    }

    public void beginReplacement(
            String supplyTypeCode, Instant collectedAt, SourceCollectionRecord record,
            List<MyHomeAnnouncementSourceSnapshot> snapshots
    ) {
        boolean hasNewerRows = rows.stream()
                .filter(row -> supplyTypeCode.equals(row.getRequestSupplyTypeCode()))
                .anyMatch(row -> row.getCollectedAt() != null && row.getCollectedAt().isAfter(collectedAt));
        if (hasNewerRows) {
            throw new IllegalArgumentException("이미 저장된 원천보다 오래된 수집 응답은 반영할 수 없습니다.");
        }
        Set<Integer> observedHouses = snapshots.stream()
                .map(MyHomeAnnouncementSourceSnapshot::houseSn).collect(Collectors.toSet());
        rows.removeIf(row -> supplyTypeCode.equals(row.getRequestSupplyTypeCode())
                && observedHouses.contains(row.getHouseSn()));
        lastCollectionRecord = record;
    }

    public void addResponseRows(
            String supplyTypeCode, Instant collectedAt, SourceCollectionRecord record,
            List<MyHomeAnnouncementSourceSnapshot> snapshots
    ) {
        int firstOrder = rows.stream().filter(row -> supplyTypeCode.equals(row.getRequestSupplyTypeCode()))
                .mapToInt(MyHomeAnnouncementSourceRow::getSourceOrder).max().orElse(-1) + 1;
        for (int index = 0; index < snapshots.size(); index++) {
            rows.add(MyHomeAnnouncementSourceRow.from(this, supplyTypeCode, collectedAt, record,
                    firstOrder + index, snapshots.get(index)));
        }
    }

    public List<MyHomeAnnouncementSourceRow> getRows() {
        return List.copyOf(rows);
    }
}
