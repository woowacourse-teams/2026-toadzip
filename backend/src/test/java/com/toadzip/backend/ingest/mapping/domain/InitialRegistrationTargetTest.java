package com.toadzip.backend.ingest.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InitialRegistrationTargetTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void 재확인으로_완료_대상의_상태와_연결을_바꾸지_않는다() {
        var target = ready();
        target.complete(10, NOW);
        target.assess("변경됨", "새 주소", InitialRegistrationStatus.LOCATION_REQUIRED, "좌표 없음", null,
                NOW.plusSeconds(100));
        assertThat(target.getStatus()).isEqualTo(InitialRegistrationStatus.COMPLETED);
        assertThat(target.getHousingComplexId()).isEqualTo(10);
        assertThat(target.getName()).isEqualTo("테스트 단지");
        assertThat(target.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void 저장_실패는_준비_상태_재확인만으로_숨기지_않는다() {
        var target = ready();
        target.fail("단지 저장 실패", NOW);
        target.assess("테스트 단지", "서울특별시 종로구 테스트로 1", InitialRegistrationStatus.READY, "준비됨", null, NOW);
        assertThat(target.getStatus()).isEqualTo(InitialRegistrationStatus.FAILED);
        assertThat(target.getDetail()).isEqualTo("단지 저장 실패");
    }

    @Test
    void 원천_수집만으로_완료_처리할_수_없다() {
        var target = InitialRegistrationTarget.create("1:HAPPY_HOUSING");
        target.assess("단지", null, InitialRegistrationStatus.LOCATION_REQUIRED, "좌표 필요", null, NOW);
        assertThatThrownBy(() -> target.complete(10, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private InitialRegistrationTarget ready() {
        var target = InitialRegistrationTarget.create("1:HAPPY_HOUSING");
        target.assess("테스트 단지", "서울특별시 종로구 테스트로 1", InitialRegistrationStatus.READY, "준비됨", null, NOW);
        return target;
    }
}
