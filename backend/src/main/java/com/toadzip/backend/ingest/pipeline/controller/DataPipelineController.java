package com.toadzip.backend.ingest.pipeline.controller;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ingest/pipelines")
public class DataPipelineController {

    private final DataPipelineExecutionService executionService;

    public DataPipelineController(DataPipelineExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/{type}")
    public ResponseEntity<DataPipelineExecutionResponse> start(@PathVariable String type) {
        DataPipelineExecutionResponse response = executionService.start(
                DataPipelineType.fromPathValue(type)
        );
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{type}")
    public ResponseEntity<DataPipelineExecutionResponse> findLatest(@PathVariable String type) {
        DataPipelineExecutionResponse response = executionService.findLatest(
                DataPipelineType.fromPathValue(type)
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<DataPipelineExecutionResponse> find(
            @PathVariable UUID executionId
    ) {
        return ResponseEntity.ok(executionService.find(executionId));
    }
}
