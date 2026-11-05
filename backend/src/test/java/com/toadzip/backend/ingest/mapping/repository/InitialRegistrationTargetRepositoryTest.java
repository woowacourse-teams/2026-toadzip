package com.toadzip.backend.ingest.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(InitialRegistrationProductStore.class)
class InitialRegistrationTargetRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    @Autowired private EntityManager entityManager;
    @Autowired private InitialRegistrationTargetRepository targets;
    @Autowired private InitialRegistrationProductStore products;

    @Test
    void 백_곳_중_완료한_아흔_곳과_남은_열_곳을_다시_읽고_필터와_정렬로_조회한다() {
        IntStream.rangeClosed(1, 100).forEach(index -> {
            var target = InitialRegistrationTarget.create("%03d:HAPPY_HOUSING".formatted(index));
            target.assess("단지 " + index, "주소", InitialRegistrationStatus.READY, "준비됨", null, NOW);
            if (index <= 90) {
                target.complete(index, NOW);
            }
            entityManager.persist(target);
        });
        entityManager.flush();
        entityManager.clear();

        var completed = targets.findById("001:HAPPY_HOUSING").orElseThrow();
        completed.assess("변경 시도", null, InitialRegistrationStatus.LOCATION_REQUIRED, "주소 없음", null, NOW);
        entityManager.flush();
        entityManager.clear();

        var remaining = targets.findTargets(InitialRegistrationStatus.READY, PageRequest.of(1, 3));
        assertThat(targets.findTargets(null, PageRequest.of(0, 50)).getTotalElements()).isEqualTo(100);
        assertThat(remaining.getTotalElements()).isEqualTo(10);
        assertThat(remaining.getContent()).extracting(InitialRegistrationTarget::getSourceComplexIdentifier)
                .containsExactly("094:HAPPY_HOUSING", "095:HAPPY_HOUSING", "096:HAPPY_HOUSING");
        assertThat(targets.countByStatus()).anySatisfy(count -> {
            assertThat(count.getStatus()).isEqualTo(InitialRegistrationStatus.COMPLETED);
            assertThat(count.getTotal()).isEqualTo(90);
        });
        assertThat(targets.findById("001:HAPPY_HOUSING").orElseThrow().getHousingComplexId()).isEqualTo(1);
    }

    @Test
    void 제품과_연결_기록은_실제_서비스_테이블에서_읽는다() {
        var complex = HousingComplex.createFromMyHome("등록 단지", "123:HAPPY_HOUSING", "HAPPY_HOUSING",
                Address.create("서울특별시 종로구 테스트로 1", "1111010100100010000", "1111010100", "11", "11110",
                        new BigDecimal("37.5"), new BigDecimal("127")), 100, "LH", null, null, null, null, null, 100);
        entityManager.persist(complex);
        entityManager.persist(MyHomeComplexLink.connect("456:HAPPY_HOUSING", complex));
        entityManager.flush();
        entityManager.clear();

        assertThat(products.findProducts()).anySatisfy(product -> {
            assertThat(product.id()).isEqualTo(complex.getId());
            assertThat(product.identifier()).isEqualTo("123:HAPPY_HOUSING");
            assertThat(product.roadAddress()).isEqualTo("서울특별시 종로구 테스트로 1");
            assertThat(product.deleted()).isFalse();
        });
        assertThat(products.findLinks()).contains(new InitialRegistrationProductStore.Link(
                "456:HAPPY_HOUSING", complex.getId()));
    }
}
