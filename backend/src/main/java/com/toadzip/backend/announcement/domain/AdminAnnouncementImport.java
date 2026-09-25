package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "admin_announcement_imports",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_admin_announcement_import_original_url", columnNames = "original_url"),
                @UniqueConstraint(name = "uk_admin_announcement_import_hash", columnNames = "json_hash"),
                @UniqueConstraint(
                        name = "uk_admin_announcement_import_source_document",
                        columnNames = "source_document_id"
                )
        }
)
@NoArgsConstructor(access = PROTECTED)
public class AdminAnnouncementImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String schemaVersion;

    @Column(nullable = false, length = 255)
    private String originalUrl;

    @Column(length = 255)
    private String sourceDocumentId;

    @Column(nullable = false, length = 64)
    private String jsonHash;

    @Column(nullable = false, columnDefinition = "text")
    private String originalJson;

    @Column(nullable = false, length = 255)
    private String registeredBy;

    @Column(nullable = false)
    private OffsetDateTime registeredAt;

    @OneToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false, unique = true)
    private Announcement announcement;

    private AdminAnnouncementImport(
            String schemaVersion,
            String originalUrl,
            String sourceDocumentId,
            String jsonHash,
            String originalJson,
            String registeredBy,
            OffsetDateTime registeredAt,
            Announcement announcement
    ) {
        this.schemaVersion = required(schemaVersion, "스키마 버전");
        this.originalUrl = required(originalUrl, "원문 URL");
        this.sourceDocumentId = optional(sourceDocumentId);
        this.jsonHash = required(jsonHash, "JSON 해시");
        this.originalJson = required(originalJson, "원본 JSON");
        this.registeredBy = required(registeredBy, "등록 관리자");
        this.registeredAt = required(registeredAt, "등록 시각");
        this.announcement = required(announcement, "공고");
    }

    public static AdminAnnouncementImport create(
            String schemaVersion,
            String originalUrl,
            String sourceDocumentId,
            String jsonHash,
            String originalJson,
            String registeredBy,
            OffsetDateTime registeredAt,
            Announcement announcement
    ) {
        return new AdminAnnouncementImport(
                schemaVersion,
                originalUrl,
                sourceDocumentId,
                jsonHash,
                originalJson,
                registeredBy,
                registeredAt,
                announcement
        );
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        return required(value, "원천 공고번호");
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
        return value;
    }

    private static <T> T required(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
        return value;
    }
}
