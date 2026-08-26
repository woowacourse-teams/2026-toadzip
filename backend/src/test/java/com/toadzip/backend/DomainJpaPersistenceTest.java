package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.interest.domain.FavoriteAnnouncement;
import com.toadzip.backend.interest.domain.FavoriteHousingComplex;
import com.toadzip.backend.interest.domain.FavoriteRegion;
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
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@SpringBootTest
@ActiveProfiles("test")
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
                        WHERE LOWER(table_name) = 'announcements'
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
            "favorite_announcements, user_id, NO",
            "favorite_announcements, announcement_id, NO",
            "favorite_housing_complexes, user_id, NO",
            "favorite_housing_complexes, housing_complex_id, NO",
            "favorite_regions, user_id, NO",
            "housing_types, housing_complex_id, NO",
            "announcements, previous_announcement_id, YES",
            "supply_rows, announcement_id, NO",
            "supply_rows, housing_complex_id, YES",
            "supply_rows, housing_type_id, YES",
            "supply_targets, supply_row_id, NO",
            "announcement_schedules, announcement_id, NO",
            "announcement_attachments, announcement_id, NO"
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
        Announcement originalAnnouncement = createAnnouncement(
                null,
                null,
                null,
                "source-announcement-id-1",
                "원공고"
        );
        entityManager.persist(originalAnnouncement);

        Announcement firstCorrectedAnnouncement = createAnnouncement(
                originalAnnouncement,
                "source-announcement-id-1",
                "접수일 변경",
                "source-announcement-id-2",
                "정정공고"
        );
        entityManager.persist(firstCorrectedAnnouncement);
        entityManager.flush();

        Announcement secondCorrectedAnnouncement = createAnnouncement(
                originalAnnouncement,
                "source-announcement-id-1",
                "발표일 변경",
                "source-announcement-id-3",
                "정정공고"
        );

        assertThrows(
                PersistenceException.class,
                () -> {
                    entityManager.persist(secondCorrectedAnnouncement);
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

        Announcement originalAnnouncement = createAnnouncement(
                null,
                null,
                null,
                "source-announcement-id-1",
                "원공고"
        );
        entityManager.persist(originalAnnouncement);

        Announcement correctedAnnouncement = createAnnouncement(
                originalAnnouncement,
                "source-announcement-id-1",
                "접수일 변경",
                "source-announcement-id-2",
                "정정공고"
        );
        entityManager.persist(correctedAnnouncement);

        SupplyRow matchedSupplyRow = createSupplyRow(correctedAnnouncement, housingComplex, housingType, 1, null);
        SupplyRow unmatchedSupplyRow = createSupplyRow(
                correctedAnnouncement,
                null,
                null,
                2,
                "단지 식별자 불일치"
        );
        entityManager.persist(matchedSupplyRow);
        entityManager.persist(unmatchedSupplyRow);

        SupplyTarget supplyTarget = createSupplyTarget(matchedSupplyRow);
        AnnouncementSchedule announcementSchedule = createAnnouncementSchedule(correctedAnnouncement);
        AnnouncementAttachment announcementAttachment = createAnnouncementAttachment(correctedAnnouncement);
        entityManager.persist(supplyTarget);
        entityManager.persist(announcementSchedule);
        entityManager.persist(announcementAttachment);

        FavoriteAnnouncement favoriteAnnouncement = FavoriteAnnouncement.create(
                user,
                correctedAnnouncement,
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
        entityManager.persist(favoriteAnnouncement);
        entityManager.persist(favoriteHousingComplex);
        entityManager.persist(favoriteRegion);

        entityManager.flush();

        Long userId = user.getId();
        Long userPlaceId = userPlace.getId();
        Long housingTypeId = housingType.getId();
        Long correctedAnnouncementId = correctedAnnouncement.getId();
        Long matchedSupplyRowId = matchedSupplyRow.getId();
        Long unmatchedSupplyRowId = unmatchedSupplyRow.getId();
        Long supplyTargetId = supplyTarget.getId();
        Long announcementScheduleId = announcementSchedule.getId();
        Long announcementAttachmentId = announcementAttachment.getId();
        Long favoriteAnnouncementId = favoriteAnnouncement.getId();
        Long favoriteHousingComplexId = favoriteHousingComplex.getId();
        Long favoriteRegionId = favoriteRegion.getId();

        entityManager.clear();

        UserEligibilityInfo foundUserEligibilityInfo = entityManager.find(UserEligibilityInfo.class, userId);
        UserPlace foundUserPlace = entityManager.find(UserPlace.class, userPlaceId);
        HousingType foundHousingType = entityManager.find(HousingType.class, housingTypeId);
        Announcement foundCorrectedAnnouncement = entityManager.find(Announcement.class, correctedAnnouncementId);
        SupplyRow foundMatchedSupplyRow = entityManager.find(SupplyRow.class, matchedSupplyRowId);
        SupplyRow foundUnmatchedSupplyRow = entityManager.find(SupplyRow.class, unmatchedSupplyRowId);
        SupplyTarget foundSupplyTarget = entityManager.find(SupplyTarget.class, supplyTargetId);
        AnnouncementSchedule foundAnnouncementSchedule = entityManager.find(
                AnnouncementSchedule.class,
                announcementScheduleId
        );
        AnnouncementAttachment foundAnnouncementAttachment = entityManager.find(
                AnnouncementAttachment.class,
                announcementAttachmentId
        );
        FavoriteAnnouncement foundFavoriteAnnouncement = entityManager.find(
                FavoriteAnnouncement.class,
                favoriteAnnouncementId
        );
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
                () -> assertEquals(
                        originalAnnouncement.getId(),
                        foundCorrectedAnnouncement.getPreviousAnnouncement().getId()
                ),
                () -> assertEquals(
                        "source-announcement-id-1",
                        foundCorrectedAnnouncement.getPreviousSourceAnnouncementIdentifier()
                ),
                () -> assertEquals(housingComplex.getId(), foundMatchedSupplyRow.getHousingComplex().getId()),
                () -> assertEquals(housingTypeId, foundMatchedSupplyRow.getHousingType().getId()),
                () -> assertEquals(YearMonth.of(2027, 3), foundMatchedSupplyRow.getExpectedMoveInMonth()),
                () -> assertNull(foundUnmatchedSupplyRow.getHousingComplex()),
                () -> assertNull(foundUnmatchedSupplyRow.getHousingType()),
                () -> assertEquals(matchedSupplyRowId, foundSupplyTarget.getSupplyRow().getId()),
                () -> assertEquals(correctedAnnouncementId, foundAnnouncementSchedule.getAnnouncement().getId()),
                () -> assertEquals(correctedAnnouncementId, foundAnnouncementAttachment.getAnnouncement().getId()),
                () -> assertEquals(correctedAnnouncementId, foundFavoriteAnnouncement.getAnnouncement().getId()),
                () -> assertEquals(housingComplex.getId(), foundFavoriteHousingComplex.getHousingComplex().getId()),
                () -> assertEquals(userId, foundFavoriteRegion.getUser().getId()),
                () -> assertEquals("11", foundFavoriteRegion.getProvinceCode()),
                () -> assertEquals("11140", foundFavoriteRegion.getCityCountyDistrictCode()),
                () -> assertNotNull(foundCorrectedAnnouncement.getReceptionPlace())
        );
    }

    @Test
    void 공고_코드는_정식값으로_저장하고_명시된_기존값과_null_공급값을_읽는다() {
        Announcement announcement = Announcement.create(
                "source-announcement-id-legacy",
                null,
                null,
                "행복주택 모집공고",
                "원공고",
                "행복주택",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/legacy",
                null,
                100L,
                new BigDecimal("1.2500"),
                new BigDecimal("2.5000"),
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")
        );
        entityManager.persist(announcement);

        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                null,
                null,
                "source-supply-row-id-legacy",
                1,
                "원문 단지",
                "36A",
                "1114010100100010000",
                null,
                SupplyCategory.NEW_SUPPLY,
                "미매칭",
                null
        );
        entityManager.persist(supplyRow);

        SupplyTarget supplyTarget = SupplyTarget.create(
                supplyRow,
                "청년",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1
        );
        AnnouncementSchedule schedule = AnnouncementSchedule.create(
                announcement,
                ScheduleType.APPLICATION,
                "인터넷 접수",
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 14, 17, 0),
                1
        );
        AnnouncementAttachment attachment = AnnouncementAttachment.create(
                announcement,
                "모집공고문.pdf",
                AttachmentType.ANNOUNCEMENT,
                "https://example.com/files/announcement.pdf",
                1
        );
        entityManager.persist(supplyTarget);
        entityManager.persist(schedule);
        entityManager.persist(attachment);
        entityManager.flush();

        Object[] storedCodes = (Object[]) entityManager.createNativeQuery(
                        """
                        SELECT status, supply_type, recruitment_type, provider, reception_method
                        FROM announcements
                        WHERE id = :announcementId
                        """
                )
                .setParameter("announcementId", announcement.getId())
                .getSingleResult();

        assertAll(
                () -> assertEquals("ORIGINAL", storedCodes[0]),
                () -> assertEquals("HAPPY_HOUSING", storedCodes[1]),
                () -> assertEquals("NEW", storedCodes[2]),
                () -> assertEquals("LH", storedCodes[3]),
                () -> assertEquals("ONLINE", storedCodes[4])
        );

        entityManager.createNativeQuery(
                        """
                        UPDATE announcements
                        SET status = '정정공고', supply_type = '행복주택', recruitment_type = '신규모집',
                            provider = '한국토지주택공사', reception_method = '인터넷'
                        WHERE id = :announcementId
                        """
                )
                .setParameter("announcementId", announcement.getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                        "UPDATE announcement_schedules SET schedule_type = '접수' WHERE id = :scheduleId"
                )
                .setParameter("scheduleId", schedule.getId())
                .executeUpdate();
        entityManager.createNativeQuery(
                        "UPDATE announcement_attachments SET file_type = '공고문' WHERE id = :attachmentId"
                )
                .setParameter("attachmentId", attachment.getId())
                .executeUpdate();
        entityManager.clear();

        Announcement foundAnnouncement = entityManager.find(Announcement.class, announcement.getId());
        SupplyRow foundSupplyRow = entityManager.find(SupplyRow.class, supplyRow.getId());
        SupplyTarget foundSupplyTarget = entityManager.find(SupplyTarget.class, supplyTarget.getId());
        AnnouncementSchedule foundSchedule = entityManager.find(AnnouncementSchedule.class, schedule.getId());
        AnnouncementAttachment foundAttachment = entityManager.find(AnnouncementAttachment.class, attachment.getId());

        assertAll(
                () -> assertEquals(AnnouncementPublicationType.CORRECTION, foundAnnouncement.getStatus()),
                () -> assertEquals(RentalType.HAPPY_HOUSING, foundAnnouncement.getSupplyType()),
                () -> assertEquals(RecruitmentType.NEW, foundAnnouncement.getRecruitmentType()),
                () -> assertEquals(AgencyCode.LH, foundAnnouncement.getProvider()),
                () -> assertEquals(ReceptionMethod.ONLINE, foundAnnouncement.getReceptionPlace().getMethod()),
                () -> assertEquals(new BigDecimal("1.2500"), foundAnnouncement.getActualCompetitionRate()),
                () -> assertEquals(new BigDecimal("2.5000"), foundAnnouncement.getPredictedCompetitionRate()),
                () -> assertEquals(ScheduleType.APPLICATION, foundSchedule.getScheduleType()),
                () -> assertEquals(AttachmentType.ANNOUNCEMENT, foundAttachment.getFileType()),
                () -> assertNull(foundSupplyRow.getExpectedMoveInMonth()),
                () -> assertNull(foundSupplyRow.getTotalSupplyHouseholdCount()),
                () -> assertNull(foundSupplyTarget.getSupplyRank()),
                () -> assertNull(foundSupplyTarget.getSupplyHouseholdCount()),
                () -> assertNull(foundSupplyTarget.getReserveCount()),
                () -> assertNull(foundSupplyTarget.getRentalDeposit()),
                () -> assertNull(foundSupplyTarget.getMonthlyRent()),
                () -> assertNull(foundSupplyTarget.getConvertedDeposit()),
                () -> assertNull(foundSupplyTarget.getApplicationCondition())
        );
    }

    @ParameterizedTest
    @CsvSource({
            "announcements, status",
            "announcements, supply_type",
            "announcements, recruitment_type",
            "announcements, provider",
            "announcements, reception_method",
            "announcement_schedules, schedule_type",
            "announcement_attachments, file_type",
            "supply_rows, supply_category"
    })
    void DB는_허용되지_않은_enum_값을_거부한다(String tableName, String columnName) {
        Announcement announcement = createAnnouncement(
                null,
                null,
                null,
                "invalid-enum-source",
                "원공고"
        );
        entityManager.persist(announcement);
        SupplyRow supplyRow = createSupplyRow(announcement, null, null, 1, "미매칭");
        AnnouncementSchedule schedule = createAnnouncementSchedule(announcement);
        AnnouncementAttachment attachment = createAnnouncementAttachment(announcement);
        entityManager.persist(supplyRow);
        entityManager.persist(schedule);
        entityManager.persist(attachment);
        entityManager.flush();
        Map<String, Long> rowIds = Map.of(
                "announcements", announcement.getId(),
                "announcement_schedules", schedule.getId(),
                "announcement_attachments", attachment.getId(),
                "supply_rows", supplyRow.getId()
        );

        String updateSql = "UPDATE " + tableName
                + " SET " + columnName + " = 'INVALID_ENUM_VALUE' WHERE id = :id";

        assertThrows(
                PersistenceException.class,
                () -> entityManager.createNativeQuery(updateSql)
                        .setParameter("id", rowIds.get(tableName))
                        .executeUpdate()
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

    private Announcement createAnnouncement(
            Announcement previousAnnouncement,
            String previousSourceAnnouncementIdentifier,
            String correctionCancellationReason,
            String sourceAnnouncementIdentifier,
            String status
    ) {
        return Announcement.create(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                previousAnnouncement,
                "행복주택 모집공고",
                status,
                "행복주택",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/" + sourceAnnouncementIdentifier,
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
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            int displayOrder,
            String matchingFailureReason
    ) {
        return SupplyRow.create(
                announcement,
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

    private AnnouncementSchedule createAnnouncementSchedule(Announcement announcement) {
        return AnnouncementSchedule.create(
                announcement,
                "접수",
                "인터넷 접수",
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 14, 17, 0),
                1
        );
    }

    private AnnouncementAttachment createAnnouncementAttachment(Announcement announcement) {
        return AnnouncementAttachment.create(
                announcement,
                "모집공고문.pdf",
                "공고문",
                "https://example.com/files/announcement.pdf",
                1
        );
    }
}
