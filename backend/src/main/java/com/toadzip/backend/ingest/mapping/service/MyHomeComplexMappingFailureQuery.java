package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class MyHomeComplexMappingFailureQuery {

    private final MyHomeComplexMappingFailureRepository failureRepository;

    MyHomeComplexMappingFailureQuery(MyHomeComplexMappingFailureRepository failureRepository) {
        this.failureRepository = failureRepository;
    }

    @Transactional(readOnly = true)
    List<MyHomeComplexMappingFailureResponse> findAll() {
        return failureRepository.findAllByStatusOrderBySourceKeyAsc(PENDING)
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    List<MyHomeComplexMappingFailureResponse> findHistory() {
        return failureRepository.findAllByOrderBySourceKeyAsc()
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }
}
