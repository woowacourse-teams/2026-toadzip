package com.toadzip.backend.admin.quality.controller;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.ReviewTask;
import com.toadzip.backend.admin.quality.dto.SaveReviewTask;
import com.toadzip.backend.admin.quality.service.ReviewTaskService;
import com.toadzip.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quality/tasks")
public class ReviewTaskController {
    private final ReviewTaskService tasks;

    public ReviewTaskController(ReviewTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/assignees")
    public ApiResponse<List<String>> assignees() {
        return new ApiResponse<>(tasks.assignees());
    }

    @GetMapping
    public ApiResponse<AdminPage<ReviewTask>> search(Principal actor,
            @RequestParam(defaultValue = "true") boolean mine,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|OPEN|IN_PROGRESS|ON_HOLD|DONE") String state,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new ApiResponse<>(tasks.search(actor.getName(), mine, state, overdue, false, page, size));
    }

    @GetMapping("/alerts")
    public ApiResponse<AdminPage<ReviewTask>> alerts(Principal actor,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return new ApiResponse<>(tasks.search(actor.getName(), true, "", false, true, page, 20));
    }

    @GetMapping("/{resource}/{id}")
    public ApiResponse<ReviewTask> find(@PathVariable QualityResource resource, @PathVariable long id) {
        return new ApiResponse<>(tasks.find(resource, id));
    }

    @PutMapping("/{resource}/{id}")
    public ApiResponse<ReviewTask> save(@PathVariable QualityResource resource, @PathVariable long id,
            @RequestBody @Valid SaveReviewTask request, Principal actor) {
        return new ApiResponse<>(tasks.save(resource, id, request, actor.getName()));
    }
}
