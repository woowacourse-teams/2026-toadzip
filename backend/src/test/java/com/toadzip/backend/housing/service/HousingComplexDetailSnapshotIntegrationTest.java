package com.toadzip.backend.housing.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HousingComplexDetailSnapshotIntegrationTest {

    private static final String ORIGINAL_COMPLEX_NAME = "변경 전 단지";

    private static final String ORIGINAL_HOUSING_TYPE_NAME = "변경 전 주택형";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private HousingComplexQueryService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ComplexDetailQueryRepository detailRepository;

    @Test
    void 상세를_조립하는_동안_데이터가_변경되어도_한_시점의_값만_반환한다() throws Exception {
        Fixture fixture = persistFixture();
        CountDownLatch complexRead = new CountDownLatch(1);
        CountDownLatch updateCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            complexRead.countDown();
            await(updateCommitted);
            return result;
        }).when(detailRepository).findComplex(fixture.complexId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HousingComplexDetailResponse> responseFuture = executor.submit(
                    () -> service.getComplex(fixture.complexId())
            );
            assertTrue(complexRead.await(5, SECONDS));

            updateNames(fixture);
            updateCommitted.countDown();

            HousingComplexDetailResponse response = responseFuture.get(5, SECONDS);
            assertAll(
                    () -> assertEquals(ORIGINAL_COMPLEX_NAME, response.name()),
                    () -> assertEquals(ORIGINAL_HOUSING_TYPE_NAME, response.housingTypes().getFirst().name())
            );
        } finally {
            updateCommitted.countDown();
            executor.shutdownNow();
        }
    }

    private Fixture persistFixture() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            HousingComplex complex = HousingComplex.create(
                    ORIGINAL_COMPLEX_NAME,
                    "snapshot-complex",
                    "행복주택",
                    Address.create(
                            "서울특별시 중구 세종대로 110",
                            "1114010100100010000",
                            "1114010100",
                            "11",
                            "11140",
                            new BigDecimal("37.500000"),
                            new BigDecimal("126.900000")
                    ),
                    100,
                    "LH",
                    LocalDate.of(2020, 1, 1),
                    "개별난방",
                    "아파트",
                    "계단식",
                    true,
                    80,
                    null,
                    7
            );
            entityManager.persist(complex);
            HousingType housingType = HousingType.create(
                    complex,
                    ORIGINAL_HOUSING_TYPE_NAME,
                    new BigDecimal("36.12"),
                    new BigDecimal("41.10"),
                    50,
                    "https://example.com/snapshot-floor-plan.png",
                    false,
                    null
            );
            entityManager.persist(housingType);
            entityManager.flush();
            return new Fixture(complex.getId(), housingType.getId());
        });
    }

    private void updateNames(Fixture fixture) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            entityManager.createQuery("""
                            UPDATE HousingComplex housingComplex
                            SET housingComplex.name = :name
                            WHERE housingComplex.id = :complexId
                            """)
                    .setParameter("name", "변경 후 단지")
                    .setParameter("complexId", fixture.complexId())
                    .executeUpdate();
            entityManager.createQuery("""
                            UPDATE HousingType housingType
                            SET housingType.name = :name
                            WHERE housingType.id = :housingTypeId
                            """)
                    .setParameter("name", "변경 후 주택형")
                    .setParameter("housingTypeId", fixture.housingTypeId())
                    .executeUpdate();
        });
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, SECONDS));
    }

    private record Fixture(long complexId, long housingTypeId) {
    }
}
