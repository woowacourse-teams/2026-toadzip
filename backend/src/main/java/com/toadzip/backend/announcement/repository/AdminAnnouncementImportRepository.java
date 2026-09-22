package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AdminAnnouncementImport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAnnouncementImportRepository extends JpaRepository<AdminAnnouncementImport, Long> {

    boolean existsByJsonHash(String jsonHash);

    boolean existsBySourceDocumentId(String sourceDocumentId);
}
