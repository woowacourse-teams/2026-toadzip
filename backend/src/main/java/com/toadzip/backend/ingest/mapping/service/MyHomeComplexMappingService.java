package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_COMPLEX_MAPPING;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MyHomeComplexMappingService {

    private final IngestOperationLock executionLock;
    private final MyHomeComplexMappingProcessor processor;
    private final MyHomeComplexMappingFailureRepository failureRepository;

    public MyHomeComplexMappingReport mapAll() {
        return executionLock.tryRun(MYHOME_COMPLEX_MAPPING, processor::mapAll)
                .orElseThrow(this::alreadyRunning);
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailures(int page, int size) {
        return failureRepository.findAllByStatusOrderBySourceKeyAscIdAsc(PENDING, PageRequest.of(page, size))
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailures() {
        return failureRepository.findAllByStatusOrderBySourceKeyAsc(PENDING)
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailureHistory(int page, int size) {
        return failureRepository.findAllByOrderBySourceKeyAscIdAsc(PageRequest.of(page, size))
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 단지 매핑이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 단지 매핑이 이미 실행 중입니다.");
    }
}
