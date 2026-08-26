package com.toadzip.backend.ingest.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementCollectionCheckpoint;

public interface LhAnnouncementCollectionCheckpointRepository
        extends JpaRepository<LhAnnouncementCollectionCheckpoint, Long> {

    @Query("""
            select checkpoint.requestHash
            from LhAnnouncementCollectionCheckpoint checkpoint
            where checkpoint.source = :source and checkpoint.requestHash in :requestHashes
            """)
    List<String> findCompletedRequestHashes(
            @Param("source") ExternalDataSource source,
            @Param("requestHashes") Collection<String> requestHashes
    );

    @Query("""
            select distinct checkpoint.panId
            from LhAnnouncementCollectionCheckpoint checkpoint
            where checkpoint.source = :source and checkpoint.panId in :panIds
            """)
    List<String> findHistoryPanIds(
            @Param("source") ExternalDataSource source,
            @Param("panIds") Collection<String> panIds
    );

    @Modifying
    @Query(value = """
            insert into lh_announcement_collection_checkpoints
                (source, source_announcement_key, request_hash, request_description, pan_id, completed_at)
            values
                (:source, :sourceAnnouncementKey, :requestHash, :requestDescription, :panId, :completedAt)
            on conflict (source, request_hash) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("source") String source,
            @Param("sourceAnnouncementKey") String sourceAnnouncementKey,
            @Param("requestHash") String requestHash,
            @Param("requestDescription") String requestDescription,
            @Param("panId") String panId,
            @Param("completedAt") Instant completedAt
    );
}
