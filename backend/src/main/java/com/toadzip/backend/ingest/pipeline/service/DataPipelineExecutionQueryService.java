package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** 실행 이력과 상태 조회. 조회 전에 끝나지 못한 실행을 복구해 멈춘 실행이 실행 중으로 보이지 않게 한다. */
@Service
@RequiredArgsConstructor
public class DataPipelineExecutionQueryService {

    private final DataPipelineExecutionRepository executionRepository;
    private final DataPipelineExecutionMapper executionMapper;
    private final DataPipelineExecutionRecovery recovery;

    public List<DataPipelineExecutionResponse> history(int page, int size) {
        recovery.recoverIfIdle();
        var request = PageRequest.of(
                page, size, Sort.by("id").descending()
        );
        return executionRepository.findAll(request).stream()
                .map(executionMapper::response)
                .toList();
    }

    public List<DataPipelineExecutionResponse> history(int page, int size, String domain) {
        if (domain == null) {
            return history(page, size);
        }
        List<DataPipelineType> types = switch (domain) {
            case "complex" -> List.of(DataPipelineType.COMPLEX_COLLECTION, DataPipelineType.COMPLEX_REFINEMENT,
                    DataPipelineType.COMPLEX_SYNC);
            case "announcement" -> List.of(DataPipelineType.ANNOUNCEMENT_COLLECTION,
                    DataPipelineType.ANNOUNCEMENT_REFINEMENT, DataPipelineType.ANNOUNCEMENT_SYNC,
                    DataPipelineType.ANNOUNCEMENT_REGISTRATION);
            default -> throw new InvalidIngestRequestException("단지 또는 공고를 선택해 주세요.");
        };
        recovery.recoverIfIdle();
        return executionRepository.findByTypeIn(types, PageRequest.of(page, size, Sort.by("id").descending()))
                .stream().map(executionMapper::response).toList();
    }

    public DataPipelineExecutionResponse findLatest(DataPipelineType type) {
        recovery.recoverIfIdle();
        return executionRepository.findFirstByTypeOrderByIdDesc(type)
                .map(executionMapper::response)
                .orElseGet(() -> DataPipelineExecutionResponse.idle(type));
    }

    public DataPipelineExecutionResponse find(UUID executionId) {
        recovery.recoverIfIdle();
        return executionRepository.findByExecutionId(executionId)
                .map(executionMapper::response)
                .orElseThrow(() -> new DataPipelineExecutionNotFoundException(
                        "데이터 파이프라인 실행을 찾을 수 없습니다: " + executionId
                ));
    }
}
