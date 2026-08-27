package com.toadzip.backend.admin.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.admin.exception.InvalidAdminCredentialsException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.admin.repository.AdminAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminAuthenticationServiceTest {

    private AuthenticationManager authenticationManager;

    private AdminAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        authenticationService = new AdminAuthenticationService(
                mock(AdminAccountRepository.class),
                authenticationManager,
                mock(PasswordEncoder.class)
        );
    }

    @Test
    void 잘못된_자격증명은_공통_인증_실패_예외로_변환한다() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("잘못된 비밀번호"));

        assertThrows(
                InvalidAdminCredentialsException.class,
                () -> authenticationService.authenticate("admin", "wrong-password")
        );
    }

    @Test
    void 존재하지_않는_계정은_공통_인증_실패_예외로_변환한다() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다."));

        assertThrows(
                InvalidAdminCredentialsException.class,
                () -> authenticationService.authenticate("unknown", "password")
        );
    }

    @Test
    void 인증_제공자_장애는_내부_예외로_전파한다() {
        AuthenticationServiceException exception = new AuthenticationServiceException("database unavailable");
        when(authenticationManager.authenticate(any())).thenThrow(exception);

        AuthenticationServiceException actual = assertThrows(
                AuthenticationServiceException.class,
                () -> authenticationService.authenticate("admin", "password")
        );

        assertSame(exception, actual);
    }
}
