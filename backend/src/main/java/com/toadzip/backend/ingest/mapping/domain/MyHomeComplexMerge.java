package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "myhome_complex_merges")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexMerge {

    @Id
    private UUID id;
    @Column(nullable = false)
    private long representativeId;
    @Column(nullable = false)
    private int snapshotVersion;
    @Column(nullable = false)
    private int adoptedHouseholdCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Long> complexIds;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String beforeState;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String afterState;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String evidence;
    @Column(nullable = false, length = 64)
    private String previewHash;
    @Column(nullable = false, length = 2000)
    private String reason;
    @Column(nullable = false)
    private String verifiedBy;
    @Column(nullable = false)
    private Instant mergedAt;
    private Instant revertedAt;
    private String revertedBy;

    public static MyHomeComplexMerge verified(
            UUID id, List<Long> complexIds, int householdCount, String beforeState, String evidence,
            String previewHash, String reason, String verifiedBy, Instant now
    ) {
        if (id == null || complexIds.size() < 2 || householdCount <= 0
                || reason == null || reason.isBlank() || verifiedBy == null || verifiedBy.isBlank()) {
            throw new IllegalArgumentException("통합 대상, 세대수, 근거와 확인자는 필수입니다.");
        }
        MyHomeComplexMerge merge = new MyHomeComplexMerge();
        merge.id = id;
        merge.complexIds = complexIds.stream().sorted().toList();
        merge.representativeId = merge.complexIds.getFirst();
        merge.snapshotVersion = 1;
        merge.adoptedHouseholdCount = householdCount;
        merge.beforeState = beforeState;
        merge.evidence = evidence;
        merge.previewHash = previewHash;
        merge.reason = reason;
        merge.verifiedBy = verifiedBy;
        merge.mergedAt = now.truncatedTo(ChronoUnit.MICROS);
        return merge;
    }

    public void complete(String afterState) {
        this.afterState = Objects.requireNonNull(afterState);
    }

    public void revert(String actor, Instant now) {
        if (revertedAt != null) {
            throw new IllegalStateException("이미 복구된 통합입니다.");
        }
        revertedBy = actor;
        revertedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public static void requireCompatibleProducts(List<HousingComplex> complexes) {
        HousingComplex first = complexes.getFirst();
        for (HousingComplex complex : complexes) {
            if (!first.getName().equals(complex.getName())
                    || !first.getProvider().equals(complex.getProvider())
                    || !first.getSupplyType().equals(complex.getSupplyType())
                    || !first.getAddress().hasSameValues(complex.getAddress())
                    || !Objects.equals(first.getCompletionDate(), complex.getCompletionDate())
                    || !Objects.equals(first.getHeatingType(), complex.getHeatingType())
                    || !Objects.equals(first.getHousingType(), complex.getHousingType())
                    || !Objects.equals(first.getCorridorType(), complex.getCorridorType())
                    || !Objects.equals(first.getElevatorInstalled(), complex.getElevatorInstalled())
                    || first.getParkingSpaceCount() != complex.getParkingSpaceCount()) {
                throw new IllegalArgumentException("단지 공통값이 다릅니다. 통합 전에 차이를 확인해야 합니다.");
            }
        }
        singleManualValue(complexes, HousingComplex::getImageUrl);
        singleManualValue(complexes, HousingComplex::getRecentOneYearMoveOutCount);
    }

    public static <T> T singleManualValue(List<HousingComplex> complexes, Function<HousingComplex, T> field) {
        List<T> values = complexes.stream().map(field).filter(Objects::nonNull).distinct().toList();
        if (values.size() > 1) {
            throw new IllegalArgumentException("수동 입력값이 충돌합니다. 덮어쓰지 않고 통합을 보류합니다.");
        }
        return values.stream().findFirst().orElse(null);
    }
}
