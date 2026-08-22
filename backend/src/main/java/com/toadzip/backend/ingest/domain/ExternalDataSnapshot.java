package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "external_data_snapshots")
@NoArgsConstructor(access = PROTECTED)
public class ExternalDataSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalDataSource source;

    @Column(nullable = false, length = 2000)
    private String requestDescription;

    @Column(nullable = false)
    private int page;

    @Column(nullable = false)
    private Instant collectedAt;

    @Lob
    @Column(nullable = false)
    private String rawPayload;

    private ExternalDataSnapshot(
            ExternalDataSource source,
            String requestDescription,
            int page,
            Instant collectedAt,
            String rawPayload
    ) {
        validateRequired(source, "외부 데이터 출처");
        validateNotBlank(requestDescription, "조회 조건");
        validatePage(page);
        validateRequired(collectedAt, "수집 시각");
        validateNotBlank(rawPayload, "원본 응답");
        this.source = source;
        this.requestDescription = requestDescription;
        this.page = page;
        this.collectedAt = collectedAt;
        this.rawPayload = rawPayload;
    }

    public static ExternalDataSnapshot create(
            ExternalDataSource source,
            String requestDescription,
            int page,
            Instant collectedAt,
            String rawPayload
    ) {
        return new ExternalDataSnapshot(source, requestDescription, page, collectedAt, rawPayload);
    }

    private void validatePage(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");
        }
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}
