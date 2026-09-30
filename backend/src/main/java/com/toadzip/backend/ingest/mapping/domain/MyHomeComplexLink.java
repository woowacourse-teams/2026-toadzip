package com.toadzip.backend.ingest.mapping.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(name = "myhome_complex_links")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexLink {

    @Id
    @Column(length = 255)
    private String sourceComplexIdentifier;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HousingComplex housingComplex;

    private UUID mergeId;
    private Integer approvedHouseholdCount;

    private MyHomeComplexLink(String sourceComplexIdentifier, HousingComplex complex) {
        if (sourceComplexIdentifier == null || sourceComplexIdentifier.isBlank() || complex == null) {
            throw new IllegalArgumentException("원천 식별자와 연결 단지는 필수입니다.");
        }
        this.sourceComplexIdentifier = sourceComplexIdentifier;
        this.housingComplex = complex;
    }

    public static MyHomeComplexLink connect(String sourceComplexIdentifier, HousingComplex complex) {
        return new MyHomeComplexLink(sourceComplexIdentifier, complex);
    }

    public void verifyAndConnect(HousingComplex representative, UUID mergeId, int householdCount) {
        if (mergeId == null || representative == null || householdCount <= 0 || this.mergeId != null) {
            throw new IllegalArgumentException("이미 통합했거나 유효하지 않은 원천 연결입니다.");
        }
        housingComplex = representative;
        this.mergeId = mergeId;
        approvedHouseholdCount = householdCount;
    }
}
