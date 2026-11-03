package com.toadzip.backend.ingest.pipeline.controller;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineReadinessResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionQueryService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/pipelines")
public class DataPipelineController {

    private final DataPipelineExecutionService executionService;
    private final DataPipelineExecutionQueryService executionQuery;

    public DataPipelineController(
            DataPipelineExecutionService executionService, DataPipelineExecutionQueryService executionQuery
    ) {
        this.executionService = executionService;
        this.executionQuery = executionQuery;
    }

    /**
     * 실행은 본문 없이 시작하고 공공데이터포털 수집 키는 서버 설정만 쓴다. 예전 화면처럼 본문으로 키를 보내면
     * 받은 값을 쓰지도 되돌려 주지도 않고 거절해, 키를 입력하는 낡은 화면이 조용히 다른 키로 실행되지 않게 한다.
     */
    @PostMapping("/{type}")
    public ResponseEntity<DataPipelineExecutionResponse> start(
            @PathVariable String type, @RequestBody(required = false) String body
    ) {
        DataPipelineType pipelineType = DataPipelineType.fromPathValue(type);
        if (body != null && !body.isBlank()) {
            throw new InvalidIngestRequestException(
                    "수집 실행은 요청 본문을 받지 않습니다. 공공데이터포털 수집 키는 서버 설정으로만 사용합니다.");
        }
        return ResponseEntity.accepted().body(executionService.start(pipelineType));
    }

    @GetMapping("/readiness")
    public ResponseEntity<DataPipelineReadinessResponse> readiness() {
        return ResponseEntity.ok(executionService.readiness());
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<DataPipelineExecutionResponse> find(@PathVariable UUID executionId) {
        return ResponseEntity.ok(executionQuery.find(executionId));
    }

    @GetMapping("/history")
    public ResponseEntity<List<DataPipelineExecutionResponse>> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String domain
    ) {
        if (domain != null) {
            return ResponseEntity.ok(executionQuery.history(page, size, domain));
        }
        return ResponseEntity.ok(executionQuery.history(page, size));
    }

    @GetMapping("/{type}")
    public ResponseEntity<DataPipelineExecutionResponse> findLatest(@PathVariable String type) {
        DataPipelineExecutionResponse response = executionQuery.findLatest(
                DataPipelineType.fromPathValue(type)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/executions/{executionId}/stop")
    public ResponseEntity<DataPipelineExecutionResponse> stop(@PathVariable UUID executionId) {
        return ResponseEntity.accepted().body(executionService.requestStop(executionId));
    }

}
