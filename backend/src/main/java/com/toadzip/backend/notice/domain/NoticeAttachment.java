package com.toadzip.backend.notice.domain;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notice_attachments")
@NoArgsConstructor(access = PROTECTED)
public class NoticeAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType;

    @Column(nullable = false)
    private String fileUrl;

    @Column(nullable = false)
    private int displayOrder;

    private NoticeAttachment(
            Notice notice,
            String fileName,
            String fileType,
            String fileUrl,
            int displayOrder
    ) {
        validateRequired(notice, "공고");
        validateNotBlank(fileName, "파일명");
        validateNotBlank(fileType, "파일종류");
        validateNotBlank(fileUrl, "파일 URL");
        validateNonNegative(displayOrder, "표시순서");
        this.notice = notice;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileUrl = fileUrl;
        this.displayOrder = displayOrder;
    }

    public static NoticeAttachment create(
            Notice notice,
            String fileName,
            String fileType,
            String fileUrl,
            int displayOrder
    ) {
        return new NoticeAttachment(notice, fileName, fileType, fileUrl, displayOrder);
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

    private void validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }
}
