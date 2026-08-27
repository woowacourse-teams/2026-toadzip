package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementAttachmentRepository extends JpaRepository<AnnouncementAttachment, Long> {

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
