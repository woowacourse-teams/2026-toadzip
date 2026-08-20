package com.toadzip.backend.user.domain;

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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_places")
@NoArgsConstructor(access = PROTECTED)
public class UserPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal longitude;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private UserPlace(
            User user,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDateTime createdAt
    ) {
        validateRequired(user, "소유 유저");
        validateNotBlank(name, "장소명");
        validateNotBlank(address, "주소");
        validateLatitude(latitude);
        validateLongitude(longitude);
        validateRequired(createdAt, "등록일시");
        this.user = user;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.createdAt = createdAt;
    }

    public static UserPlace create(
            User user,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDateTime createdAt
    ) {
        return new UserPlace(user, name, address, latitude, longitude, createdAt);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateLatitude(BigDecimal latitude) {
        validateRequired(latitude, "위도");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("위도는 -90도 이상 90도 이하여야 한다.");
        }
    }

    private void validateLongitude(BigDecimal longitude) {
        validateRequired(longitude, "경도");
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("경도는 -180도 이상 180도 이하여야 한다.");
        }
    }
}
