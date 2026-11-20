package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.dto.AdminSessionResponse;
import com.toadzip.backend.admin.dto.CsrfTokenResponse;
import com.toadzip.backend.user.service.SocialUserService;
import com.toadzip.backend.user.service.UserLogout;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthenticationController {

    private final SocialUserService socialUserService;
    private final UserLogout userLogout;

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return CsrfTokenResponse.from(csrfToken);
    }

    @GetMapping("/me")
    public AdminSessionResponse currentAdmin(Authentication authentication) {
        return AdminSessionResponse.from(socialUserService.loginIdentifierOf(Long.valueOf(authentication.getName())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        userLogout.logout(authentication, servletRequest, servletResponse);
    }
}
