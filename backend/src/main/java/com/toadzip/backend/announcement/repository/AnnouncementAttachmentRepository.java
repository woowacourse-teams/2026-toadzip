package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.dto.response.AttachmentSource;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import com.toadzip.backend.announcement.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementAttachmentRepository extends JpaRepository<AnnouncementAttachment, Long> {

    @Query("""
            SELECT new com.toadzip.backend.announcement.dto.response.AttachmentSource(a.fileName, a.fileUrl)
            FROM AnnouncementAttachment a
            WHERE a.id = :attachmentId AND a.announcement.id = :announcementId
              AND a.announcement.adminDeleted = false
            """)
    Optional<AttachmentSource> findPublicSource(
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
