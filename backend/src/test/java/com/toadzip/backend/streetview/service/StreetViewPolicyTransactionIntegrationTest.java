package com.toadzip.backend.streetview.service;

import static com.toadzip.backend.streetview.StreetViewFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.streetview.dto.StreetViewPolicyUpdateRequest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
class StreetViewPolicyTransactionIntegrationTest {
    @Autowired private StreetViewPolicyService service;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private AdminDataChangeRepository changes;

    @BeforeEach
    void setUp() {
        clean();
        policy(jdbc, false);
        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        clean();
    }

    @Test
    void 감사_저장_실패는_정책과_버전도_롤백한다() {
        doThrow(new IllegalStateException("test audit failure")).when(changes).save(any(AdminDataChange.class));
        assertThatThrownBy(() -> service.update(new StreetViewPolicyUpdateRequest(0L, true, "활성화"), "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.get().enabled()).isFalse();
        assertThat(service.get().version()).isZero();
        assertThat(service.get().reason()).isEqualTo("테스트 초기 정책");
        assertThat(auditCount()).isZero();
    }

    @Test
    void 같은_버전의_두_트랜잭션은_하나만_성공한다() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Callable<String> update = () -> {
            authenticate();
            try {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                service.update(new StreetViewPolicyUpdateRequest(0L, true, "활성화"), "operator");
                return "SUCCESS";
            } catch (AdminDataConflictException exception) {
                return "CONFLICT";
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            var first = executor.submit(update);
            var second = executor.submit(update);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            assertThat(service.get().version()).isEqualTo(1);
            assertThat(auditCount()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void authenticate() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
    }

    private long auditCount() {
        return jdbc.sql("SELECT count(*) FROM admin_data_changes WHERE resource_type = 'STREET_VIEW_POLICY'")
                .query(Long.class).single();
    }

    private void clean() {
        jdbc.sql("DELETE FROM admin_data_changes WHERE resource_type = 'STREET_VIEW_POLICY'").update();
        jdbc.sql("DELETE FROM street_view_policies").update();
    }
}
