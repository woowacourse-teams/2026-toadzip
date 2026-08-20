package com.toadzip.backend.interest.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

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
@Table(name = "favorite_regions")
@NoArgsConstructor(access = PROTECTED)
public class FavoriteRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provinceCode;

    @Column(nullable = false)
    private String cityCountyDistrictCode;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private FavoriteRegion(
            User user,
            String provinceCode,
            String cityCountyDistrictCode,
            LocalDateTime createdAt
    ) {
        validateRequired(user, "유저");
        validateNotBlank(provinceCode, "시·도 코드");
        validateNotBlank(cityCountyDistrictCode, "시·군·구 코드");
        validateRequired(createdAt, "등록일시");
        this.user = user;
        this.provinceCode = provinceCode;
        this.cityCountyDistrictCode = cityCountyDistrictCode;
        this.createdAt = createdAt;
    }

    public static FavoriteRegion create(
            User user,
            String provinceCode,
            String cityCountyDistrictCode,
            LocalDateTime createdAt
    ) {
        return new FavoriteRegion(
                user,
                provinceCode,
                cityCountyDistrictCode,
                createdAt
        );
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
