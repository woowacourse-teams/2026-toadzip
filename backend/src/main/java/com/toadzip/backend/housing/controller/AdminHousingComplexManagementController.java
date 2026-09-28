package com.toadzip.backend.housing.controller;

import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexDetail;
import com.toadzip.backend.housing.service.AdminHousingComplexManagementService;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/housing-complexes")
public class AdminHousingComplexManagementController {
    private final AdminHousingComplexManagementService service;
    private final IngestExecutionOwnershipService ownership;
    public AdminHousingComplexManagementController(AdminHousingComplexManagementService service, IngestExecutionOwnershipService ownership) {
        this.service = service;
        this.ownership = ownership;
    }

    @GetMapping
    public ApiResponse<AdminPage<AdminDataSummary>> search(@Valid @ModelAttribute AdminSearch search) {
        return new ApiResponse<>(service.search(search));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminHousingComplexDetail> detail(@PathVariable long id) {
        return new ApiResponse<>(service.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminHousingComplexDetail> update(@PathVariable long id,
            @Valid @RequestBody AdminHousingComplexUpdateRequest request, Principal principal) {
        try (var lease = ownership.acquire()) {
            return new ApiResponse<>(service.update(id, request, principal.getName()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> trash(@PathVariable long id, @RequestParam @Min(0) long version,
            Principal principal) {
        try (var lease = ownership.acquire()) {
            service.trash(id, version, false, principal.getName());
            return ResponseEntity.noContent().build();
        }
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable long id, @RequestParam @Min(0) long version,
            Principal principal) {
        try (var lease = ownership.acquire()) {
            service.trash(id, version, true, principal.getName());
            return ResponseEntity.noContent().build();
        }
    }

    @GetMapping("/{id}/changes")
    public ApiResponse<List<AdminChangeResponse>> history(@PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return new ApiResponse<>(service.history(id, page));
    }

}
