package com.toadzip.backend.user.controller;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.user.dto.AdminUserProvider;
import com.toadzip.backend.user.dto.AdminUserSummary;
import com.toadzip.backend.user.service.AdminUserQueryService;
import com.toadzip.backend.user.service.AdminUserRoleService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserManagementController {

    private final AdminUserQueryService service;
    private final AdminUserRoleService roles;

    public AdminUserManagementController(AdminUserQueryService service, AdminUserRoleService roles) {
        this.service = service;
        this.roles = roles;
    }

    @GetMapping
    public ApiResponse<AdminPage<AdminUserSummary>> search(
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(required = false) AdminUserProvider provider,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return new ApiResponse<>(service.search(keyword.strip(), provider, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserSummary> detail(@PathVariable long id) {
        return new ApiResponse<>(service.detail(id));
    }

    @PutMapping("/{id}/admin")
    public ApiResponse<AdminUserSummary> grantAdmin(@PathVariable long id, Authentication actor) {
        roles.grant(id, actor.getName());
        return new ApiResponse<>(service.detail(id));
    }

    @DeleteMapping("/{id}/admin")
    public ApiResponse<AdminUserSummary> revokeAdmin(@PathVariable long id, Authentication actor) {
        roles.revoke(id, actor);
        return new ApiResponse<>(service.detail(id));
    }
}
