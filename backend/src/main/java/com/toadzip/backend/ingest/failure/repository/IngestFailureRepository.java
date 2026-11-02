package com.toadzip.backend.ingest.failure.repository;

import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface IngestFailureRepository<T extends IngestFailure<T>> extends JpaRepository<T, Long> {

    List<T> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<T> findAllByStatus(IngestFailureStatus status);

    List<T> findAllByStatusOrderBySourceKeyAscIdAsc(IngestFailureStatus status, Pageable pageable);

    List<T> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);
}
