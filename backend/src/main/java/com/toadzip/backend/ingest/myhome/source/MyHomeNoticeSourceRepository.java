package com.toadzip.backend.ingest.myhome.source;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeNoticeSourceRepository extends JpaRepository<MyHomeNoticeSource, Long> {

	Optional<MyHomeNoticeSource> findBySourceKey(String sourceKey);

	List<MyHomeNoticeSource> findAllByOrderBySourceOrderAsc();

}
