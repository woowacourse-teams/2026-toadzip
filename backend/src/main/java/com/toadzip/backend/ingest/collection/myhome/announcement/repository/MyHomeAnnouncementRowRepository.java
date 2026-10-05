package com.toadzip.backend.ingest.collection.myhome.announcement.repository;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceRow;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

public interface MyHomeAnnouncementRowRepository extends JpaRepository<MyHomeAnnouncementSourceRow, Long> {

    List<MyHomeAnnouncementSourceRow> findByIdGreaterThanOrderByIdAsc(Long id, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT row FROM MyHomeAnnouncementSourceRow row WHERE row.active = true "
            + "AND (row.lastSeenRunId IS NULL OR row.lastSeenRunId <> :runId) "
            + "AND (row.collectedAt IS NULL OR row.collectedAt <= :startedAt) ORDER BY row.id")
    List<MyHomeAnnouncementSourceRow> findActiveNotSeen(String runId, Instant startedAt);
}
