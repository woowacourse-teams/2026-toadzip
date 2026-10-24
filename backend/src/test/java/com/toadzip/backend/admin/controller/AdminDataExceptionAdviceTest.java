package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class AdminDataExceptionAdviceTest {
    private final AdminDataExceptionAdvice advice = new AdminDataExceptionAdvice();

    @Test
    void 다시_조회하면_풀리는_충돌만_STALE_코드로_구분한다() {
        var stale = advice.conflict(new AdminDataStaleException("등록값이나 원천이 바뀌었습니다."));
        var locked = advice.conflict(new ObjectOptimisticLockingFailureException("SupplyRow", 1L));
        var rule = advice.conflict(new AdminDataConflictException("제외·연결 변경할 단지에 접수 일정이 있습니다."));

        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody()).containsEntry("code", "ADMIN_DATA_STALE").containsEntry("message", "등록값이나 원천이 바뀌었습니다.");
        assertThat(locked.getBody()).containsEntry("code", "ADMIN_DATA_STALE")
                .containsEntry("message", "데이터가 변경되었습니다. 새로 조회한 뒤 다시 시도해 주세요.");
        assertThat(rule.getStatusCode().value()).isEqualTo(409);
        assertThat(rule.getBody()).containsEntry("code", "ADMIN_DATA_CONFLICT")
                .containsEntry("message", "제외·연결 변경할 단지에 접수 일정이 있습니다.");
    }
}
