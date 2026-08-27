package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementScheduleRepository extends JpaRepository<AnnouncementSchedule, Long> {

    @Query("""
            SELECT schedule
            FROM AnnouncementSchedule schedule
            WHERE schedule.announcement.id IN :announcementIds
            ORDER BY schedule.announcement.id ASC, schedule.displayOrder ASC, schedule.id ASC
            """)
    List<AnnouncementSchedule> findAllByAnnouncementIdIn(
            @Param("announcementIds") Collection<Long> announcementIds
    );
}
