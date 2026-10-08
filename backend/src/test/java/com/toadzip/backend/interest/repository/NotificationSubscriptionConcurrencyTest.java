package com.toadzip.backend.interest.repository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class NotificationSubscriptionConcurrencyTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 이메일은_남아있지만_충돌한_신청은_제거된_상황을_준비한다() {
        doReturn(List.of(1L), List.of(), List.of()).when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void 충돌한_회원_신청이_잠그기_전에_제거되면_성공으로_반환하지_않는다() {
        NotificationSubscriptionRepository repository = new NotificationSubscriptionRepository(jdbcTemplate);

        assertThrows(ConcurrencyFailureException.class,
                () -> repository.activate(1L, NotificationTargetType.COMPLEX, "1", Instant.now()));
    }

    @Test
    void 충돌한_비회원_신청이_잠그기_전에_제거되면_성공으로_반환하지_않는다() {
        NotificationGuestSubscriptionRepository repository = new NotificationGuestSubscriptionRepository(jdbcTemplate);

        assertThrows(ConcurrencyFailureException.class,
                () -> repository.activate(UUID.randomUUID(), NotificationTargetType.COMPLEX, "1", Instant.now()));
    }
}
