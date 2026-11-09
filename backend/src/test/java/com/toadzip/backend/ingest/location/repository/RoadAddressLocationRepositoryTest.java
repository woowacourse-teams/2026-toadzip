package com.toadzip.backend.ingest.location.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RoadAddressLocationRepositoryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private RoadAddressLocationRepository locations;

    @Test
    void 좌표를_조회할_때마다_원천과_등록_대상의_저장_여부를_반복_검사하지_않는다() {
        var target = InitialRegistrationTarget.create("123:HAPPY_HOUSING");
        target.assess("단지", "서울특별시 중구 세종대로 110", InitialRegistrationStatus.LOCATION_REQUIRED,
                "좌표 확인 필요", null, Instant.parse("2026-11-06T00:00:00Z"));
        entityManager.persist(target);
        entityManager.flush();
        target.assess("변경한 단지", target.getRoadAddress(), InitialRegistrationStatus.LOCATION_REQUIRED,
                "확인 중", null, Instant.parse("2026-11-06T01:00:00Z"));
        var inspections = new AtomicInteger();
        entityManager.unwrap(Session.class).addEventListeners(new SessionEventListener() {
            @Override
            public void dirtyCalculationStart() {
                inspections.incrementAndGet();
            }
        });

        for (int index = 0; index < 10; index++) {
            assertThat(locations.findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
                    "서울특별시 중구 세종대로 " + index)).isEmpty();
        }

        assertThat(inspections).hasValue(0);
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(InitialRegistrationTarget.class, "123:HAPPY_HOUSING").getName())
                .isEqualTo("변경한 단지");
    }
}
