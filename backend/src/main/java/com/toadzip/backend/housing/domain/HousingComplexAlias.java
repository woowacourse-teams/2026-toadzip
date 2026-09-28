package com.toadzip.backend.housing.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "housing_complex_aliases")
@NoArgsConstructor(access = PROTECTED)
public class HousingComplexAlias {

    @Id
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HousingComplex housingComplex;

    @Column(nullable = false)
    private UUID mergeId;
}
