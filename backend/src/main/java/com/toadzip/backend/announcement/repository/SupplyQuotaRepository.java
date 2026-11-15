package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyQuota;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplyQuotaRepository extends JpaRepository<SupplyQuota, Long> {

    @Query("""
            SELECT DISTINCT quota
            FROM SupplyQuota quota
            LEFT JOIN FETCH quota.members member
            LEFT JOIN FETCH member.supplyRow
            WHERE quota.announcement.id IN :announcementIds
            """)
    List<SupplyQuota> findAllByAnnouncementIdIn(@Param("announcementIds") Collection<Long> announcementIds);
}
