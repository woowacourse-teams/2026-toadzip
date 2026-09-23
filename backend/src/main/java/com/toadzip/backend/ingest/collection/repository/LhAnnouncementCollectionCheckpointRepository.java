package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LhAnnouncementCollectionCheckpointRepository
        extends JpaRepository<LhAnnouncementCollectionCheckpoint, Long> {

    @Query("""
            select checkpoint.requestHash
            from LhAnnouncementCollectionCheckpoint checkpoint
            where checkpoint.source = :source
                and checkpoint.requestHash in :requestHashes
                and checkpoint.completedAt > :freshCompletedAfter
            """)
    List<String> findFreshRequestHashes(
            @Param("source") ExternalDataSource source,
            @Param("requestHashes") Collection<String> requestHashes,
            @Param("freshCompletedAfter") Instant freshCompletedAfter
    );

    @Modifying
    @Query(value = """
            insert into lh_announcement_collection_checkpoints
                (source, source_announcement_key, request_hash, request_description, pan_id, completed_at)
            values
                (:source, :sourceAnnouncementKey, :requestHash, :requestDescription, :panId, :completedAt)
            on conflict (source, request_hash) do update set
                source_announcement_key = excluded.source_announcement_key,
                request_description = excluded.request_description,
                pan_id = excluded.pan_id,
                completed_at = excluded.completed_at
            """, nativeQuery = true)
    int upsert(
            @Param("source") String source,
            @Param("sourceAnnouncementKey") String sourceAnnouncementKey,
            @Param("requestHash") String requestHash,
            @Param("requestDescription") String requestDescription,
            @Param("panId") String panId,
            @Param("completedAt") Instant completedAt
    );
}
