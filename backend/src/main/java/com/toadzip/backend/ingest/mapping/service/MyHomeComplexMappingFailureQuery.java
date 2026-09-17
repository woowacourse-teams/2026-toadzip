package com.toadzip.backend.ingest.mapping.service;

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
        return failureRepository.findAllByOrderBySourceKeyAsc()
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }
}
