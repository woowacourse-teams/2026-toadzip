package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementComplexRepository extends JpaRepository<AnnouncementComplex, Long> {

    List<AnnouncementComplex> findAllByAnnouncementOrderByIdAsc(Announcement announcement);
}
