package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgencySupplyLineRepository extends JpaRepository<AgencySupplyLine, Long> {

    List<AgencySupplyLine> findAllByAnnouncement(Announcement announcement);

    /** 공고들의 준비 줄을 공고 단지·연결 단지와 함께 공고, 표시 순서, ID 순서로. */
    @Query("""
            SELECT line
            FROM AgencySupplyLine line
            LEFT JOIN FETCH line.announcementComplex complex
            LEFT JOIN FETCH complex.housingComplex
            WHERE line.announcement.id IN :announcementIds
            ORDER BY line.announcement.id ASC, line.displayOrder ASC, line.id ASC
            """)
    List<AgencySupplyLine> findAllByAnnouncementIdIn(@Param("announcementIds") Collection<Long> announcementIds);
}
