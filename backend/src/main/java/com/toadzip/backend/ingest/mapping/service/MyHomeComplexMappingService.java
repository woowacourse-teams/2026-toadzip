package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MyHomeComplexMappingService {

    private final MyHomeComplexMappingProcessor processor;
    private final MyHomeComplexMappingFailureStore failureStore;

    public MyHomeComplexMappingReport mapAll() {
        IngestExecutionScope.requireHeld();
        return processor.mapAll();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailures(int page, int size) {
        return failureStore.pending(page, size)
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailureHistory(int page, int size) {
        return failureStore.history(page, size)
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }
}
