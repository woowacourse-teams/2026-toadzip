package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LhAnnouncementDetailSourceRepository extends JpaRepository<LhAnnouncementDetailSource, Long> {

    List<LhAnnouncementDetailSource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId,
            String requestHash
    );

    @Modifying(flushAutomatically = true)
    @Query("delete from LhAnnouncementDetailSource source "
            + "where source.panId = :panId and source.requestHash = :requestHash")
    void deleteByPanIdAndRequestHash(@Param("panId") String panId, @Param("requestHash") String requestHash);
}
