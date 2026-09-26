package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnnouncementApplicationScheduleRepository
        extends JpaRepository<AnnouncementApplicationSchedule, Long> {

    @Query("""
            select schedule from AnnouncementApplicationSchedule schedule
            left join fetch schedule.housingComplex
            where schedule.announcement.id in :announcementIds
            order by schedule.startDate, schedule.startTime, schedule.id
            """)
    List<AnnouncementApplicationSchedule> findAllByAnnouncementIdIn(Collection<Long> announcementIds);
}
