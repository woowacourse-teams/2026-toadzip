package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.dto.AdminLoginRequest;
import com.toadzip.backend.admin.dto.AdminSessionResponse;
import com.toadzip.backend.admin.dto.CsrfTokenResponse;
import com.toadzip.backend.admin.service.AdminAuthenticationAuditService;
import com.toadzip.backend.admin.service.AdminAuthenticationService;
import com.toadzip.backend.admin.service.InvalidAdminCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthenticationController {

    private final AdminAuthenticationService adminAuthenticationService;
    private final AdminAuthenticationAuditService adminAuthenticationAuditService;
    private final SecurityContextRepository securityContextRepository;

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return CsrfTokenResponse.from(csrfToken);
    }

    @PostMapping("/login")
    public AdminSessionResponse login(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        try {
            return loginAndCreateSession(request, servletRequest, servletResponse);
        } catch (InvalidAdminCredentialsException exception) {
            adminAuthenticationAuditService.recordLoginFailure(
                    request.loginIdentifier(),
                    servletRequest.getRequestId()
            );
            throw exception;
        }
    }

    @GetMapping("/me")
    public AdminSessionResponse currentAdmin(Authentication authentication) {
        return AdminSessionResponse.from(authentication.getName());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
        adminAuthenticationAuditService.recordLogout(authentication.getName(), servletRequest.getRequestId());
    }

    private AdminSessionResponse loginAndCreateSession(
            AdminLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        Authentication authentication = adminAuthenticationService.authenticate(
                request.loginIdentifier(),
                request.password()
        );
        servletRequest.getSession();
        servletRequest.changeSessionId();
        saveAuthentication(authentication, servletRequest, servletResponse);
        adminAuthenticationAuditService.recordLoginSuccess(authentication.getName(), servletRequest.getRequestId());
        return AdminSessionResponse.from(authentication.getName());
    }

    private void saveAuthentication(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, servletRequest, servletResponse);
    }
}
