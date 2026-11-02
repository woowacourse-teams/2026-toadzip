package com.toadzip.backend.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.admin.repository.AdminAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "app.admin.bootstrap.enabled=true",
        "app.admin.bootstrap.login-identifier=bootstrap-test-admin",
        "app.admin.bootstrap.password=bootstrap-test-password"
})
@ActiveProfiles("test")
@DirtiesContext
class AdminBootstrapIntegrationTest {
    @Autowired private AdminAccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void 시작할_때_설정한_관리자_계정을_만든다() {
        var account = accounts.findByLoginIdentifier("bootstrap-test-admin").orElseThrow();

        assertThat(passwordEncoder.matches("bootstrap-test-password", account.getPasswordHash())).isTrue();
    }
}
