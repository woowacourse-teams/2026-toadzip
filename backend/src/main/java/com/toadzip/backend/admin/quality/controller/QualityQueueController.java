package com.toadzip.backend.admin.quality.controller;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.dto.QualityQueueItem;
import com.toadzip.backend.admin.quality.service.QualityQueueService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quality/queue")
public class QualityQueueController {
    private final QualityQueueService queue;

    public QualityQueueController(QualityQueueService queue) {
        this.queue = queue;
    }

    @GetMapping
    public ApiResponse<AdminPage<QualityQueueItem>> search(
            @RequestParam(defaultValue = "") @Pattern(regexp = "|COMPLEX|ANNOUNCEMENT") String resource,
            @RequestParam(defaultValue = "")
            @Pattern(regexp = "|MISSING|SOURCE_MISMATCH|STALE|UNREVIEWED|ON_HOLD|SOURCE_CHANGED") String reason,
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new ApiResponse<>(queue.search(resource, reason, keyword, page, size));
    }
}
