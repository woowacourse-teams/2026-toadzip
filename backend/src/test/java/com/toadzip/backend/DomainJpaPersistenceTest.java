package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.interest.domain.FavoriteHousingComplex;
import com.toadzip.backend.interest.domain.FavoriteNotice;
import com.toadzip.backend.interest.domain.FavoriteRegion;
import com.toadzip.backend.notice.domain.Notice;
import com.toadzip.backend.notice.domain.NoticeAttachment;
import com.toadzip.backend.notice.domain.NoticeSchedule;
import com.toadzip.backend.notice.domain.ReceptionPlace;
import com.toadzip.backend.notice.domain.SupplyCategory;
import com.toadzip.backend.notice.domain.SupplyRow;
import com.toadzip.backend.notice.domain.SupplyTarget;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.domain.UserEligibilityInfo;
import com.toadzip.backend.user.domain.UserPlace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@PostgreSqlIntegrationTest
class DomainJpaPersistenceTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void 영속성_통합_테스트는_PostgreSQL에서_실행한다() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void 접수처_연락처_컬럼은_null을_허용하지_않는다() {
        Object isNullable = entityManager.createNativeQuery(
                        """
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE LOWER(table_name) = 'notices'
                          AND LOWER(column_name) = 'reception_contact'
                        """
                )
                .getSingleResult();

        assertEquals("NO", isNullable);
    }

    @ParameterizedTest
    @CsvSource({
            "user_eligibility_infos, user_id, NO",
            "user_places, user_id, NO",
            "favorite_notices, user_id, NO",
            "favorite_notices, notice_id, NO",
            "favorite_housing_complexes, user_id, NO",
            "favorite_housing_complexes, housing_complex_id, NO",
            "favorite_regions, user_id, NO",
            "housing_types, housing_complex_id, NO",
            "notices, previous_notice_id, YES",
            "supply_rows, notice_id, NO",
            "supply_rows, housing_complex_id, YES",
            "supply_rows, housing_type_id, YES",
            "supply_targets, supply_row_id, NO",
            "notice_schedules, notice_id, NO",
            "notice_attachments, notice_id, NO"
    })
    void 연관관계_외래키의_null_허용_여부를_보장한다(
            String tableName,
            String columnName,
            String expectedNullability
    ) {
        Object actualNullability = entityManager.createNativeQuery(
                        """
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE LOWER(table_name) = :tableName
                          AND LOWER(column_name) = :columnName
                        """
                )
                .setParameter("tableName", tableName)
                .setParameter("columnName", columnName)
                .getSingleResult();

        assertEquals(expectedNullability, actualNullability);
    }

    @Test
    void 하나의_이전_공고는_하나의_후속_공고만_참조한다() {
        Notice originalNotice = createNotice(null, null, null, "source-notice-id-1", "원공고");
        entityManager.persist(originalNotice);

        Notice firstCorrectedNotice = createNotice(
                originalNotice,
                "source-notice-id-1",
                "접수일 변경",
                "source-notice-id-2",
                "정정공고"
        );
        entityManager.persist(firstCorrectedNotice);
        entityManager.flush();

        Notice secondCorrectedNotice = createNotice(
                originalNotice,
                "source-notice-id-1",
                "발표일 변경",
                "source-notice-id-3",
                "정정공고"
        );

        assertThrows(
                PersistenceException.class,
                () -> {
                    entityManager.persist(secondCorrectedNotice);
                    entityManager.flush();
                }
        );
    }

    @Test
    void ERD의_연관관계와_값을_저장하고_다시_조회한다() {
        User user = createUser();
        entityManager.persist(user);

        UserEligibilityInfo userEligibilityInfo = createUserEligibilityInfo(user);
        UserPlace userPlace = createUserPlace(user);
        entityManager.persist(userEligibilityInfo);
        entityManager.persist(userPlace);

        HousingComplex housingComplex = createHousingComplex();
        entityManager.persist(housingComplex);

        HousingType housingType = createHousingType(housingComplex);
        entityManager.persist(housingType);

        Notice originalNotice = createNotice(null, null, null, "source-notice-id-1", "원공고");
        entityManager.persist(originalNotice);

        Notice correctedNotice = createNotice(
                originalNotice,
                "source-notice-id-1",
                "접수일 변경",
                "source-notice-id-2",
                "정정공고"
        );
        entityManager.persist(correctedNotice);

        SupplyRow matchedSupplyRow = createSupplyRow(correctedNotice, housingComplex, housingType, 1, null);
        SupplyRow unmatchedSupplyRow = createSupplyRow(
                correctedNotice,
                null,
                null,
                2,
                "단지 식별자 불일치"
        );
        entityManager.persist(matchedSupplyRow);
        entityManager.persist(unmatchedSupplyRow);

        SupplyTarget supplyTarget = createSupplyTarget(matchedSupplyRow);
        NoticeSchedule noticeSchedule = createNoticeSchedule(correctedNotice);
        NoticeAttachment noticeAttachment = createNoticeAttachment(correctedNotice);
        entityManager.persist(supplyTarget);
        entityManager.persist(noticeSchedule);
        entityManager.persist(noticeAttachment);

        FavoriteNotice favoriteNotice = FavoriteNotice.create(
                user,
                correctedNotice,
                LocalDateTime.of(2026, 8, 19, 13, 0)
        );
        FavoriteHousingComplex favoriteHousingComplex = FavoriteHousingComplex.create(
                user,
                housingComplex,
                LocalDateTime.of(2026, 8, 19, 13, 2)
        );
        FavoriteRegion favoriteRegion = FavoriteRegion.create(
                user,
                "11",
                "11140",
                LocalDateTime.of(2026, 8, 19, 13, 3)
        );
        entityManager.persist(favoriteNotice);
        entityManager.persist(favoriteHousingComplex);
        entityManager.persist(favoriteRegion);

        entityManager.flush();

        Long userId = user.getId();
        Long userPlaceId = userPlace.getId();
        Long housingTypeId = housingType.getId();
        Long correctedNoticeId = correctedNotice.getId();
        Long matchedSupplyRowId = matchedSupplyRow.getId();
        Long unmatchedSupplyRowId = unmatchedSupplyRow.getId();
        Long supplyTargetId = supplyTarget.getId();
        Long noticeScheduleId = noticeSchedule.getId();
        Long noticeAttachmentId = noticeAttachment.getId();
        Long favoriteNoticeId = favoriteNotice.getId();
        Long favoriteHousingComplexId = favoriteHousingComplex.getId();
        Long favoriteRegionId = favoriteRegion.getId();

        entityManager.clear();

        UserEligibilityInfo foundUserEligibilityInfo = entityManager.find(UserEligibilityInfo.class, userId);
        UserPlace foundUserPlace = entityManager.find(UserPlace.class, userPlaceId);
        HousingType foundHousingType = entityManager.find(HousingType.class, housingTypeId);
        Notice foundCorrectedNotice = entityManager.find(Notice.class, correctedNoticeId);
        SupplyRow foundMatchedSupplyRow = entityManager.find(SupplyRow.class, matchedSupplyRowId);
        SupplyRow foundUnmatchedSupplyRow = entityManager.find(SupplyRow.class, unmatchedSupplyRowId);
        SupplyTarget foundSupplyTarget = entityManager.find(SupplyTarget.class, supplyTargetId);
        NoticeSchedule foundNoticeSchedule = entityManager.find(NoticeSchedule.class, noticeScheduleId);
        NoticeAttachment foundNoticeAttachment = entityManager.find(NoticeAttachment.class, noticeAttachmentId);
        FavoriteNotice foundFavoriteNotice = entityManager.find(FavoriteNotice.class, favoriteNoticeId);
        FavoriteHousingComplex foundFavoriteHousingComplex = entityManager.find(
                FavoriteHousingComplex.class,
                favoriteHousingComplexId
        );
        FavoriteRegion foundFavoriteRegion = entityManager.find(FavoriteRegion.class, favoriteRegionId);

        assertAll(
                () -> assertEquals(userId, foundUserEligibilityInfo.getUserId()),
                () -> assertEquals(userId, foundUserEligibilityInfo.getUser().getId()),
                () -> assertEquals(userId, foundUserPlace.getUser().getId()),
                () -> assertEquals(0, new BigDecimal("37.5665").compareTo(foundUserPlace.getLatitude())),
                () -> assertEquals(0, new BigDecimal("126.9780").compareTo(foundUserPlace.getLongitude())),
                () -> assertEquals(housingComplex.getId(), foundHousingType.getHousingComplex().getId()),
                () -> assertEquals(
                        0,
                        new BigDecimal("37.5665").compareTo(
                                foundHousingType.getHousingComplex().getAddress().getLatitude()
                        )
                ),
                () -> assertEquals(
                        0,
                        new BigDecimal("126.9780").compareTo(
                                foundHousingType.getHousingComplex().getAddress().getLongitude()
                        )
                ),
                () -> assertEquals(originalNotice.getId(), foundCorrectedNotice.getPreviousNotice().getId()),
                () -> assertEquals("source-notice-id-1", foundCorrectedNotice.getPreviousSourceNoticeIdentifier()),
                () -> assertEquals(housingComplex.getId(), foundMatchedSupplyRow.getHousingComplex().getId()),
                () -> assertEquals(housingTypeId, foundMatchedSupplyRow.getHousingType().getId()),
                () -> assertEquals(YearMonth.of(2027, 3), foundMatchedSupplyRow.getExpectedMoveInMonth()),
                () -> assertNull(foundUnmatchedSupplyRow.getHousingComplex()),
                () -> assertNull(foundUnmatchedSupplyRow.getHousingType()),
                () -> assertEquals(matchedSupplyRowId, foundSupplyTarget.getSupplyRow().getId()),
                () -> assertEquals(correctedNoticeId, foundNoticeSchedule.getNotice().getId()),
                () -> assertEquals(correctedNoticeId, foundNoticeAttachment.getNotice().getId()),
                () -> assertEquals(correctedNoticeId, foundFavoriteNotice.getNotice().getId()),
                () -> assertEquals(housingComplex.getId(), foundFavoriteHousingComplex.getHousingComplex().getId()),
                () -> assertEquals(userId, foundFavoriteRegion.getUser().getId()),
                () -> assertEquals("11", foundFavoriteRegion.getProvinceCode()),
                () -> assertEquals("11140", foundFavoriteRegion.getCityCountyDistrictCode()),
                () -> assertNotNull(foundCorrectedNotice.getReceptionPlace())
        );
    }

    private User createUser() {
        return User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
    }

    private UserEligibilityInfo createUserEligibilityInfo(User user) {
        BigDecimal income = new BigDecimal("3000000");
        return UserEligibilityInfo.create(
                user,
                LocalDate.of(1995, 5, 10),
                "서울특별시",
                true,
                3,
                false,
                true,
                "기혼",
                LocalDate.of(2024, 3, 1),
                new BigDecimal("2500000"),
                1,
                false,
                true,
                income,
                new BigDecimal("5500000"),
                new BigDecimal("6000000"),
                new BigDecimal("150000000"),
                new BigDecimal("20000000"),
                true,
                LocalDate.of(2020, 1, 15),
                36,
                false,
                false,
                false,
                false,
                LocalDate.of(2026, 2, 1)
        );
    }

    private UserPlace createUserPlace(User user) {
        return UserPlace.create(
                user,
                "회사",
                "서울특별시 중구 세종대로 110",
                new BigDecimal("37.5665"),
                new BigDecimal("126.9780"),
                LocalDateTime.of(2026, 8, 19, 12, 30)
        );
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "두꺼비 행복주택",
                "source-complex-id",
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780")
                ),
                500,
                "LH",
                LocalDate.of(2020, 6, 30),
                "개별난방",
                "아파트",
                "계단식",
                true,
                420,
                "https://example.com/complex.png",
                25
        );
    }

    private HousingType createHousingType(HousingComplex housingComplex) {
        return HousingType.create(
                housingComplex,
                "36A",
                new BigDecimal("36.00"),
                new BigDecimal("48.00"),
                120,
                "https://example.com/floor-plan.png",
                false,
                new BigDecimal("120000")
        );
    }

    private Notice createNotice(
            Notice previousNotice,
            String previousSourceNoticeIdentifier,
            String correctionCancellationReason,
            String sourceNoticeIdentifier,
            String status
    ) {
        return Notice.create(
                sourceNoticeIdentifier,
                previousSourceNoticeIdentifier,
                previousNotice,
                "행복주택 모집공고",
                status,
                "행복주택",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/notices/" + sourceNoticeIdentifier,
                correctionCancellationReason,
                100L,
                ReceptionPlace.create(
                        "LH 청약센터",
                        "인터넷",
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )
        );
    }

    private SupplyRow createSupplyRow(
            Notice notice,
            HousingComplex housingComplex,
            HousingType housingType,
            int displayOrder,
            String matchingFailureReason
    ) {
        return SupplyRow.create(
                notice,
                housingComplex,
                housingType,
                "source-supply-row-id-" + displayOrder,
                displayOrder,
                "원천 단지명",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                matchingFailureReason,
                20
        );
    }

    private SupplyTarget createSupplyTarget(SupplyRow supplyRow) {
        return SupplyTarget.create(
                supplyRow,
                "청년",
                "1순위",
                10,
                20,
                new BigDecimal("50000000"),
                new BigDecimal("250000"),
                new BigDecimal("70000000"),
                "소득 기준 충족",
                1
        );
    }

    private NoticeSchedule createNoticeSchedule(Notice notice) {
        return NoticeSchedule.create(
                notice,
                "접수",
                "인터넷 접수",
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 14, 17, 0),
                1
        );
    }

    private NoticeAttachment createNoticeAttachment(Notice notice) {
        return NoticeAttachment.create(
                notice,
                "모집공고문.pdf",
                "공고문",
                "https://example.com/files/notice.pdf",
                1
        );
    }
}
