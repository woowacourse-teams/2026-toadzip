package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementPdfExtractionRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementPdfExtractionRecordRepository extends JpaRepository<AnnouncementPdfExtractionRecord, Long> {
    Optional<AnnouncementPdfExtractionRecord> findFirstByAnnouncementIdOrderByCreatedAtDescIdDesc(long announcementId);
}
