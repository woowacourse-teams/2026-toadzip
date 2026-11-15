package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.LeaseTerms;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaseTermsRepository extends JpaRepository<LeaseTerms, Long> {

    @Query("""
            SELECT DISTINCT lease
            FROM LeaseTerms lease
            LEFT JOIN FETCH lease.members member
            LEFT JOIN FETCH member.supplyRow
            WHERE lease.announcement.id IN :announcementIds
            """)
    List<LeaseTerms> findAllByAnnouncementIdIn(@Param("announcementIds") Collection<Long> announcementIds);
}
