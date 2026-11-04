package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.dto.response.AttachmentSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementAttachmentRepository extends JpaRepository<AnnouncementAttachment, Long> {

    @Query("""
            SELECT new com.toadzip.backend.announcement.dto.response.AttachmentSource(a.fileName, a.fileUrl)
            FROM AnnouncementAttachment a
            WHERE a.id = :attachmentId AND a.announcement.id = :announcementId
              AND a.announcement.adminDeleted = false
              AND a.announcement.releaseStatus = com.toadzip.backend.announcement.domain.AnnouncementReleaseStatus.PUBLISHED
            """)
    Optional<AttachmentSource> findPublicSource(
            @Param("announcementId") long announcementId,
            @Param("attachmentId") long attachmentId
    );

    /** 관리자 PDF 추출용. 공개 전인 미확정 공고의 첨부도 읽는다. */
    @Query("""
            SELECT new com.toadzip.backend.announcement.dto.response.AttachmentSource(a.fileName, a.fileUrl)
            FROM AnnouncementAttachment a
            WHERE a.id = :attachmentId AND a.announcement.id = :announcementId
              AND a.announcement.adminDeleted = false
            """)
    Optional<AttachmentSource> findAdminSource(
            @Param("announcementId") long announcementId,
            @Param("attachmentId") long attachmentId
    );

    List<AnnouncementAttachment> findAllByAnnouncement(Announcement announcement);

    @Query("""
            SELECT attachment
            FROM AnnouncementAttachment attachment
            WHERE attachment.announcement.id IN :announcementIds
            ORDER BY attachment.announcement.id ASC, attachment.displayOrder ASC, attachment.id ASC
            """)
    List<AnnouncementAttachment> findAllByAnnouncementIdIn(
            @Param("announcementIds") Collection<Long> announcementIds
    );
}
