package com.toadzip.backend.interest.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "favorite_housing_complexes")
@NoArgsConstructor(access = PROTECTED)
public class FavoriteHousingComplex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    private HousingComplex housingComplex;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private FavoriteHousingComplex(User user, HousingComplex housingComplex, LocalDateTime createdAt) {
        validateRequired(user, "유저");
        validateRequired(housingComplex, "단지");
        validateRequired(createdAt, "등록일시");
        this.user = user;
        this.housingComplex = housingComplex;
        this.createdAt = createdAt;
    }

    public static FavoriteHousingComplex create(
            User user,
            HousingComplex housingComplex,
            LocalDateTime createdAt
    ) {
        return new FavoriteHousingComplex(user, housingComplex, createdAt);
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
