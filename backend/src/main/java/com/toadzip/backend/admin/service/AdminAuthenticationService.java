package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.exception.InvalidAdminCredentialsException;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuthenticationService {

    private final AdminAccountRepository adminAccountRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;

    public Authentication authenticate(String loginIdentifier, String password) {
        Authentication authentication = authenticateCredentials(loginIdentifier, password);
        return authentication;
    }

    @Transactional
    public void bootstrap(AdminBootstrapProperties properties) {
        if (!properties.enabled()) {
            return;
        }
        validateBootstrapProperties(properties);
        if (adminAccountRepository.findByLoginIdentifier(properties.loginIdentifier()).isPresent()) {
            return;
        }
        AdminAccount adminAccount = AdminAccount.create(
                properties.loginIdentifier(),
                passwordEncoder.encode(properties.password()),
                LocalDateTime.now()
        );
        adminAccountRepository.save(adminAccount);
    }

    private Authentication authenticateCredentials(String loginIdentifier, String password) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(loginIdentifier, password)
            );
        } catch (BadCredentialsException | UsernameNotFoundException exception) {
            throw new InvalidAdminCredentialsException();
        }
    }

    private void validateBootstrapProperties(AdminBootstrapProperties properties) {
        if (properties.loginIdentifier() == null || properties.loginIdentifier().isBlank()) {
            throw new IllegalStateException("ADMIN_LOGIN_IDENTIFIER를 설정해야 합니다.");
        }
        if (properties.password() == null || properties.password().isBlank()) {
            throw new IllegalStateException("ADMIN_PASSWORD를 설정해야 합니다.");
        }
    }
}
