package com.toadzip.backend.ingest.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.toadzip.backend.ingest.domain.MyHomeNoticeSource;

public interface MyHomeNoticeSourceRepository extends JpaRepository<MyHomeNoticeSource, Long> {

    List<MyHomeNoticeSource> findAllBySourceKeyIn(Collection<String> sourceKeys);

    @Query("select coalesce(max(source.sourceOrder), -1) from MyHomeNoticeSource source")
    int findMaxSourceOrder();
}
