package com.toadzip.backend.announcement.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementQueryRepositoryTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private AnnouncementScheduleRepository announcementScheduleRepository;

    @Autowired
    private AnnouncementAttachmentRepository announcementAttachmentRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private SupplyTargetRepository supplyTargetRepository;

    private Announcement originalAnnouncement;
    private Announcement correctionAnnouncement;
    private Announcement cancellationAnnouncement;
    private Announcement sameDateLeafAnnouncement;
    private Announcement olderLeafAnnouncement;
    private AnnouncementSchedule firstCancellationSchedule;
    private AnnouncementSchedule secondCancellationSchedule;
    private AnnouncementSchedule sameDateLeafSchedule;
    private AnnouncementAttachment firstCancellationAttachment;
    private AnnouncementAttachment secondCancellationAttachment;
    private AnnouncementAttachment sameDateLeafAttachment;
    private SupplyRow matchedSupplyRow;
    private SupplyRow unmatchedSupplyRow;
    private SupplyRow sameDateLeafSupplyRow;
    private SupplyTarget firstMatchedTarget;
    private SupplyTarget secondMatchedTarget;
    private SupplyTarget unmatchedTarget;
    private SupplyTarget sameDateLeafTarget;

    @BeforeEach
    void setUp() {
        originalAnnouncement = persist(createAnnouncement(
                "original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1)
        ));
        correctionAnnouncement = persist(createAnnouncement(
                "correction",
                originalAnnouncement,
                "original",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 2)
        ));
        cancellationAnnouncement = persist(createAnnouncement(
                "cancellation",
                correctionAnnouncement,
                "correction",
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 3)
        ));
        sameDateLeafAnnouncement = persist(createAnnouncement(
                "same-date-leaf",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 3)
        ));
        olderLeafAnnouncement = persist(createAnnouncement(
                "older-leaf",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 2)
        ));

        createOrderedSchedules();
        createOrderedAttachments();
        createSupplyGraph();
        entityManager.flush();
    }

    @Test
    void 취소되지_않은_최신_후속공고만_게시일과_ID_내림차순으로_조회한다() {
        List<Announcement> announcements = announcementRepository.findLatestLeaves(pageable(10));

        List<Long> actualIds = announcements.stream().map(Announcement::getId).toList();
        assertEquals(
                List.of(
                        sameDateLeafAnnouncement.getId(),
                        olderLeafAnnouncement.getId()
                ),
                actualIds
        );
        assertFalse(actualIds.contains(originalAnnouncement.getId()));
        assertFalse(actualIds.contains(correctionAnnouncement.getId()));
        assertFalse(actualIds.contains(cancellationAnnouncement.getId()));
    }

    @Test
    void 같은_게시일의_커서_뒤부터_중복_없이_이어_조회한다() {
        List<Announcement> firstPage = announcementRepository.findLatestLeaves(pageable(1));
        Announcement cursorAnnouncement = firstPage.getFirst();

        List<Announcement> secondPage = announcementRepository.findLatestLeavesAfter(
                cursorAnnouncement.getPostedDate(),
                cursorAnnouncement.getId(),
                pageable(10)
        );

        List<Long> combinedIds = List.of(firstPage.getFirst().getId(), secondPage.getFirst().getId());
        assertEquals(
                List.of(olderLeafAnnouncement.getId()),
                secondPage.stream().map(Announcement::getId).toList()
        );
        assertEquals(combinedIds.size(), new HashSet<>(combinedIds).size());
    }

    @Test
    void 기존_미연결_정정공고는_목록과_커서_조회에서_제외한다() {
        Announcement unlinkedCorrection = persist(createAnnouncement(
                "legacy-unlinked-correction",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 5)
        ));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE announcements SET status = 'CORRECTION' WHERE id = :id")
                .setParameter("id", unlinkedCorrection.getId())
                .executeUpdate();
        entityManager.clear();

        List<Long> latestIds = announcementRepository.findLatestLeaves(pageable(20)).stream()
                .map(Announcement::getId)
                .toList();
        List<Long> cursorIds = announcementRepository.findLatestLeavesAfter(
                        LocalDate.of(2026, 8, 6),
                        Long.MAX_VALUE,
                        pageable(20)
                ).stream()
                .map(Announcement::getId)
                .toList();

        assertFalse(latestIds.contains(unlinkedCorrection.getId()));
        assertFalse(cursorIds.contains(unlinkedCorrection.getId()));
    }

    @Test
    void 상세_조회는_연결된_모든_공고를_ID로_조회한다() {
        Long originalId = originalAnnouncement.getId();
        Long correctionId = correctionAnnouncement.getId();
        Long cancellationId = cancellationAnnouncement.getId();

        assertTrue(announcementRepository.findDetailById(cancellationId).isPresent());
        assertTrue(announcementRepository.findDetailById(originalId).isPresent());
        assertTrue(announcementRepository.findDetailById(correctionId).isPresent());
    }

    @Test
    void 기존_한글값으로_저장된_원공고도_목록과_상세에서_공개한다() {
        Announcement legacyOriginal = persist(createAnnouncement(
                "legacy-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 5)
        ));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE announcements SET status = '원공고' WHERE id = :id")
                .setParameter("id", legacyOriginal.getId())
                .executeUpdate();
        entityManager.clear();

        List<Long> latestIds = announcementRepository.findLatestLeaves(pageable(20)).stream()
                .map(Announcement::getId)
                .toList();

        assertTrue(latestIds.contains(legacyOriginal.getId()));
        assertTrue(announcementRepository.findDetailById(legacyOriginal.getId()).isPresent());
    }

    @Test
    void 기존_한글값으로_저장된_정정공고도_체인의_최신이면_목록에_공개한다() {
        Announcement original = persist(createAnnouncement(
                "legacy-correction-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 4)
        ));
        Announcement legacyCorrection = persist(createAnnouncement(
                "legacy-correction",
                original,
                "legacy-correction-original",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 5)
        ));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE announcements SET status = '정정공고' WHERE id = :id")
                .setParameter("id", legacyCorrection.getId())
                .executeUpdate();
        entityManager.clear();

        List<Long> latestIds = announcementRepository.findLatestLeaves(pageable(20)).stream()
                .map(Announcement::getId)
                .toList();

        assertTrue(latestIds.contains(legacyCorrection.getId()));
        assertFalse(latestIds.contains(original.getId()));
    }

    @Test
    void 일정은_공고_ID와_표시순서와_ID_오름차순으로_조회한다() {
        List<AnnouncementSchedule> schedules = announcementScheduleRepository.findAllByAnnouncementIdIn(
                List.of(sameDateLeafAnnouncement.getId(), cancellationAnnouncement.getId())
        );

        assertEquals(
                List.of(
                        firstCancellationSchedule.getId(),
                        secondCancellationSchedule.getId(),
                        sameDateLeafSchedule.getId()
                ),
                schedules.stream().map(AnnouncementSchedule::getId).toList()
        );
    }

    @Test
    void 첨부파일은_공고_ID와_표시순서와_ID_오름차순으로_조회한다() {
        List<AnnouncementAttachment> attachments = announcementAttachmentRepository.findAllByAnnouncementIdIn(
                List.of(sameDateLeafAnnouncement.getId(), cancellationAnnouncement.getId())
        );

        assertEquals(
                List.of(
                        firstCancellationAttachment.getId(),
                        secondCancellationAttachment.getId(),
                        sameDateLeafAttachment.getId()
                ),
                attachments.stream().map(AnnouncementAttachment::getId).toList()
        );
    }

    @Test
    void 공급행은_매칭되지_않은_행도_유지하고_선택_연관을_함께_조회한다() {
        Long matchedSupplyRowId = matchedSupplyRow.getId();
        Long unmatchedSupplyRowId = unmatchedSupplyRow.getId();
        Long sameDateLeafSupplyRowId = sameDateLeafSupplyRow.getId();
        entityManager.clear();

        List<SupplyRow> supplyRows = supplyRowRepository.findAllByAnnouncementIdIn(
                List.of(sameDateLeafAnnouncement.getId(), cancellationAnnouncement.getId())
        );

        assertEquals(
                List.of(matchedSupplyRowId, unmatchedSupplyRowId, sameDateLeafSupplyRowId),
                supplyRows.stream().map(SupplyRow::getId).toList()
        );
        assertTrue(entityManagerFactory.getPersistenceUnitUtil().isLoaded(supplyRows.getFirst(), "housingComplex"));
        assertTrue(entityManagerFactory.getPersistenceUnitUtil().isLoaded(supplyRows.getFirst(), "housingType"));
        entityManager.clear();
        assertEquals("조회 단지", supplyRows.getFirst().getHousingComplex().getName());
        assertEquals("36A", supplyRows.getFirst().getHousingType().getName());
        assertNull(supplyRows.get(1).getHousingComplex());
        assertNull(supplyRows.get(1).getHousingType());
    }

    @Test
    void 공급대상은_공급행_ID와_표시순서와_ID_오름차순으로_조회한다() {
        List<SupplyTarget> targets = supplyTargetRepository.findAllBySupplyRowIdIn(
                List.of(
                        sameDateLeafSupplyRow.getId(),
                        unmatchedSupplyRow.getId(),
                        matchedSupplyRow.getId()
                )
        );

        assertEquals(
                List.of(
                        firstMatchedTarget.getId(),
                        secondMatchedTarget.getId(),
                        unmatchedTarget.getId(),
                        sameDateLeafTarget.getId()
                ),
                targets.stream().map(SupplyTarget::getId).toList()
        );
    }

    @Test
    void 공고_목록_정렬_인덱스는_게시일과_ID를_순서대로_사용한다() {
        List<?> indexColumns = entityManager.createNativeQuery(
                        """
                        SELECT attribute.attname
                        FROM pg_class table_class
                        JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                        JOIN pg_index index_metadata ON index_metadata.indrelid = table_class.oid
                        JOIN pg_class index_class ON index_class.oid = index_metadata.indexrelid
                        JOIN LATERAL unnest(index_metadata.indkey::smallint[]) WITH ORDINALITY
                            AS index_key(attnum, position) ON TRUE
                        JOIN pg_attribute attribute
                            ON attribute.attrelid = table_class.oid
                            AND attribute.attnum = index_key.attnum
                        WHERE namespace.nspname = current_schema()
                          AND table_class.relname = 'announcements'
                          AND index_class.relname = 'idx_announcements_posted_date_id'
                        ORDER BY index_key.position
                        """
                )
                .getResultList();

        assertEquals(List.of("posted_date", "id"), indexColumns);
    }

    private void createOrderedSchedules() {
        firstCancellationSchedule = persist(createSchedule(cancellationAnnouncement, "취소 일정 1", 1));
        secondCancellationSchedule = persist(createSchedule(cancellationAnnouncement, "취소 일정 2", 1));
        sameDateLeafSchedule = persist(createSchedule(sameDateLeafAnnouncement, "별도 일정", 0));
    }

    private void createOrderedAttachments() {
        firstCancellationAttachment = persist(createAttachment(cancellationAnnouncement, "취소 첨부 1", 1));
        secondCancellationAttachment = persist(createAttachment(cancellationAnnouncement, "취소 첨부 2", 1));
        sameDateLeafAttachment = persist(createAttachment(sameDateLeafAnnouncement, "별도 첨부", 0));
    }

    private void createSupplyGraph() {
        HousingComplex housingComplex = persist(createHousingComplex());
        HousingType housingType = persist(createHousingType(housingComplex));
        matchedSupplyRow = persist(createSupplyRow(
                cancellationAnnouncement,
                housingComplex,
                housingType,
                "matched-row",
                1,
                null
        ));
        unmatchedSupplyRow = persist(createSupplyRow(
                cancellationAnnouncement,
                null,
                null,
                "unmatched-row",
                1,
                "단지 매칭 실패"
        ));
        sameDateLeafSupplyRow = persist(createSupplyRow(
                sameDateLeafAnnouncement,
                null,
                null,
                "same-date-row",
                0,
                "단지 매칭 실패"
        ));
        firstMatchedTarget = persist(createTarget(matchedSupplyRow, "청년", 1));
        secondMatchedTarget = persist(createTarget(matchedSupplyRow, "신혼부부", 1));
        unmatchedTarget = persist(createTarget(unmatchedSupplyRow, "일반", 0));
        sameDateLeafTarget = persist(createTarget(sameDateLeafSupplyRow, "고령자", 0));
    }

    private Announcement createAnnouncement(
            String sourceIdentifier,
            Announcement previousAnnouncement,
            String previousSourceIdentifier,
            AnnouncementPublicationType publicationType,
            LocalDate postedDate
    ) {
        return Announcement.create(
                sourceIdentifier,
                previousSourceIdentifier,
                previousAnnouncement,
                sourceIdentifier + " 공고",
                publicationType,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                postedDate,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/" + sourceIdentifier,
                null,
                0L,
                ReceptionPlace.create(
                        "LH 청약센터",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )
        );
    }

    private AnnouncementSchedule createSchedule(Announcement announcement, String name, int displayOrder) {
        return AnnouncementSchedule.create(
                announcement,
                ScheduleType.APPLICATION,
                name,
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 14, 17, 0),
                displayOrder
        );
    }

    private AnnouncementAttachment createAttachment(Announcement announcement, String fileName, int displayOrder) {
        return AnnouncementAttachment.create(
                announcement,
                fileName,
                AttachmentType.ANNOUNCEMENT,
                "https://example.com/files/" + fileName,
                displayOrder
        );
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "조회 단지",
                "query-complex",
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
                100,
                "LH",
                LocalDate.of(2020, 6, 30),
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                "https://example.com/complex.png",
                null
        );
    }

    private HousingType createHousingType(HousingComplex housingComplex) {
        return HousingType.create(
                housingComplex,
                "36A",
                new BigDecimal("36.00"),
                new BigDecimal("48.00"),
                20,
                "https://example.com/floor-plan.png",
                false,
                null
        );
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            String sourceIdentifier,
            int displayOrder,
            String matchingFailureReason
    ) {
        return SupplyRow.create(
                announcement,
                housingComplex,
                housingType,
                sourceIdentifier,
                displayOrder,
                "원문 단지",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                matchingFailureReason,
                10
        );
    }

    private SupplyTarget createTarget(SupplyRow supplyRow, String target, int displayOrder) {
        return SupplyTarget.create(
                supplyRow,
                target,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                displayOrder
        );
    }

    private Pageable pageable(int size) {
        return PageRequest.of(0, size, Sort.unsorted());
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
