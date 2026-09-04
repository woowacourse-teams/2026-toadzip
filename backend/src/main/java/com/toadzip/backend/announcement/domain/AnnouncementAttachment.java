package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;

@Getter
@Entity
@Table(name = "announcement_attachments")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    @Convert(converter = AttachmentTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private AttachmentType fileType;

    @Column(nullable = false)
    private String fileUrl;

    @Column(nullable = false)
    private int displayOrder;

    private String sourceAttachmentIdentifier;

    private AnnouncementAttachment(
            Announcement announcement,
            String fileName,
            AttachmentType fileType,
            String fileUrl,
            int displayOrder
    ) {
        validateRequired(announcement, "공고");
        validateNotBlank(fileName, "파일명");
        validateRequired(fileType, "파일종류");
        validateNotBlank(fileUrl, "파일 URL");
        validateNonNegative(displayOrder, "표시순서");
        this.announcement = announcement;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileUrl = fileUrl;
        this.displayOrder = displayOrder;
    }

    public static AnnouncementAttachment create(
            Announcement announcement,
            String fileName,
            AttachmentType fileType,
            String fileUrl,
            int displayOrder
    ) {
        return new AnnouncementAttachment(announcement, fileName, fileType, fileUrl, displayOrder);
    }

    public static AnnouncementAttachment createFromSource(
            Announcement announcement,
            String sourceAttachmentIdentifier,
            String fileName,
            AttachmentType fileType,
            String fileUrl,
            int displayOrder
    ) {
        AnnouncementAttachment attachment = create(announcement, fileName, fileType, fileUrl, displayOrder);
        attachment.sourceAttachmentIdentifier = sourceAttachmentIdentifier;
        return attachment;
    }

    public boolean updateFromSource(String fileName, AttachmentType fileType, String fileUrl, int displayOrder) {
        AnnouncementAttachment incoming = create(announcement, fileName, fileType, fileUrl, displayOrder);
        if (this.fileName.equals(incoming.fileName)
                && this.fileType == incoming.fileType
                && this.fileUrl.equals(incoming.fileUrl)
                && this.displayOrder == incoming.displayOrder) {
            return false;
        }
        this.fileName = incoming.fileName;
        this.fileType = incoming.fileType;
        this.fileUrl = incoming.fileUrl;
        this.displayOrder = incoming.displayOrder;
        return true;
    }

    public static AnnouncementAttachment create(
            Announcement announcement,
            String fileName,
            String fileType,
            String fileUrl,
            int displayOrder
    ) {
        return create(announcement, fileName, AttachmentType.fromStoredValue(fileType), fileUrl, displayOrder);
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
