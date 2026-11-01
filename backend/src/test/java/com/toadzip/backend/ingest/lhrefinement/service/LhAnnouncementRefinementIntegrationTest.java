package com.toadzip.backend.ingest.lhrefinement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementOrigin;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyOwner;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.LhSourceViews;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lhrefinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.LhSupplyMatchStatus;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementRefinementIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String ADDRESS = "경기도 화성시 동탄대로 100";

    @Autowired private LhAnnouncementRefinementService refinement;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private LhStorageFixtures lhStorage;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementComplexRepository announcementComplexes;
    @Autowired private SupplyRowRepository supplyRows;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private NotificationOutboxRepository outbox;

    private final List<Entry> catalogEntries = new ArrayList<>();
    private HousingComplex complex;
    private HousingType area59;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, lh_announcement_catalog_entries,
                    lh_announcement_query_sources, admin_data_changes, notification_outbox_events CASCADE
                """).update();
        complex = complex("동탄 행복주택", "complex-1", ADDRESS, "HAPPY_HOUSING");
        area59 = types.save(HousingType.createFromMyHome(complex, "complex-1:59", "59A", new BigDecimal("59.9800"), null));
        types.save(HousingType.createFromMyHome(complex, "complex-1:84", "84A", new BigDecimal("84.9600"), null));
    }

    @Test
    void 공고목록에만_있는_공고를_만들고_주소가_같은_단지에_자동_연결해_LH_한_행마다_공급행을_만든다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A(청년)", "59.9800", "10", "30000000", "200000"),
                supply("동탄 행복주택 단지", "59A(고령자)", "59.9800", "4", "", ""),
                supply("동탄 행복주택 단지", "46A", "46.0000", "3", "20000000", "100000"));
        long complexCount = complexes.count();
        long typeCount = types.count();

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.failedAnnouncementCount()).isZero();
        assertThat(complexes.count()).isEqualTo(complexCount);
        assertThat(types.count()).isEqualTo(typeCount);
        inTransaction(() -> {
            Announcement announcement = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst();
            assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("LH:P1");
            assertThat(announcement.origin()).isEqualTo(AnnouncementOrigin.LH_SOURCE);
            assertThat(announcement.getSupplyOwner()).isEqualTo(SupplyOwner.LH_SOURCE);
            assertThat(announcement.getSupplyType()).isEqualTo(RentalType.HAPPY_HOUSING);
            assertThat(announcement.getProvider()).isEqualTo(AgencyCode.LH);
            assertThat(announcement.getLhSupplyFingerprint()).isNotBlank();
            AnnouncementComplex announcementComplex = announcementComplexes
                    .findAllByAnnouncementOrderByIdAsc(announcement).getFirst();
            assertThat(announcementComplex.getLinkMethod()).isEqualTo(ComplexLinkMethod.AUTO);
            assertThat(announcementComplex.linkedComplex().getId()).isEqualTo(complex.getId());
            List<SupplyRow> rows = supplyRows.findAllByAnnouncement(announcement);
            // 단지에 없는 대표 면적의 행은 주택형 없이 면적 미일치로 둔다. 다른 면적의 주택형을 고르지 않는다.
            SupplyRow unmatched = rows.stream().filter(row -> row.getSourceHousingTypeName().equals("46A")).findFirst()
                    .orElseThrow();
            assertThat(unmatched.getHousingType()).isNull();
            assertThat(unmatched.getHousingTypes()).isEmpty();
            assertThat(unmatched.getLhMatchStatus()).isEqualTo(LhSupplyMatchStatus.AREA_UNMATCHED);
            rows = rows.stream().filter(row -> row != unmatched).toList();
            assertThat(rows).extracting(SupplyRow::getSourceSupplyRowIdentifier).containsExactlyInAnyOrder(
                    "LHROW:" + announcementComplex.getId() + ":59A(청년)#1",
                    "LHROW:" + announcementComplex.getId() + ":59A(고령자)#1");
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getHousingType().getId()).isEqualTo(area59.getId());
                assertThat(row.getHousingTypes()).extracting(HousingType::getId).containsExactly(area59.getId());
                assertThat(row.getSupplyPnu()).isNull();
                assertThat(row.getSupplyCategory()).isEqualTo(SupplyCategory.NEW_SUPPLY);
            });
            assertThat(rows).extracting(SupplyRow::getLhMatchStatus)
                    .containsExactlyInAnyOrder(LhSupplyMatchStatus.MATCHED, LhSupplyMatchStatus.VALUES_MISSING);
        });
        // 공고일이 7일 안인 새 공고만 새 공고 알림을 요청한다.
        verify(outbox).appendNewAnnouncement(any(Announcement.class));

        LhAnnouncementRefinementReport again = refinement.refineAll();

        assertThat(again.createdAnnouncementCount()).isZero();
        assertThat(again.updatedAnnouncementCount()).isZero();
        assertThat(again.supplyRows().created()).isZero();
        assertThat(again.supplyRows().updated()).isZero();
        assertThat(again.supplyRows().deleted()).isZero();
        assertThat(again.supplyRows().createdTargets()).isZero();
        assertThat(again.supplyRows().updatedTargets()).isZero();
        assertThat(again.announcementComplexes().created()).isZero();
        assertThat(again.announcementComplexes().updated()).isZero();
    }

    @Test
    void 같은_주소와_유형의_단지가_둘이면_연결하지_않고_공급행을_단지_미연결로_둔다() {
        complex("동탄 행복주택 2", "complex-2", ADDRESS, "HAPPY_HOUSING");
        // 공고일이 7일 넘게 지나 뒤늦게 만든 공고는 새 공고로 알리지 않는다.
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(8));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));

        refinement.refineAll();

        inTransaction(() -> {
            Announcement announcement = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst();
            assertThat(announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)).singleElement()
                    .satisfies(found -> assertThat(found.getHousingComplex()).isNull());
            assertThat(supplyRows.findAllByAnnouncement(announcement)).singleElement().satisfies(row -> {
                assertThat(row.getHousingComplex()).isNull();
                assertThat(row.getHousingType()).isNull();
                assertThat(row.getLhMatchStatus()).isEqualTo(LhSupplyMatchStatus.COMPLEX_UNLINKED);
            });
        });
        verify(outbox, never()).appendNewAnnouncement(any(Announcement.class));
    }

    @Test
    void 같은_LH_공고_ID의_기존_공고를_ID_그대로_이어받고_기존_공급행이_가리키던_단지를_옮겨_연결한다() {
        Long existingId = computeInTransaction(() -> {
            Announcement existing = announcements.save(Announcement.create("21026", null, null, "마이홈 공고",
                    AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING,
                    RecruitmentType.NEW, AgencyCode.LH, today().minusDays(30),
                    today().minusDays(20), today().minusDays(10), today().plusDays(10), "https://example.com/a", null,
                    0L, null));
            existing.enrichFromLh("P1", null, null);
            supplyRows.save(SupplyRow.create(existing, complex, area59, "21026:1", 0, "동탄 행복주택", "59A",
                    "1111010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10));
            return existing.getId();
        });
        // 다른 주소지만 기존 공급행이 이 단지를 가리켜 주소 규칙 대신 옮긴다.
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(30));
        details("P1", "동탄 행복주택 단지", "경기도 화성시 다른로 1");
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report.createdAnnouncementCount()).isZero();
        assertThat(report.adoptedAnnouncementCount()).isOne();
        assertThat(report.announcementComplexes().migrated()).isOne();
        inTransaction(() -> {
            Announcement announcement = announcements.findById(existingId).orElseThrow();
            assertThat(announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)).singleElement()
                    .satisfies(found -> {
                        assertThat(found.getLinkMethod()).isEqualTo(ComplexLinkMethod.MIGRATED);
                        assertThat(found.linkedComplex().getId()).isEqualTo(complex.getId());
                    });
            assertThat(supplyRows.findAllByAnnouncement(announcement)).singleElement().satisfies(row ->
                    assertThat(row.getSourceSupplyRowIdentifier()).startsWith("LHROW:"));
        });
        verify(outbox, never()).appendNewAnnouncement(any(Announcement.class));
    }

    @Test
    void 공고문_소유_공고는_공급행을_바꾸지_않고_공고_단지만_채우며_LH_원천이_바뀌면_재확인을_켠다() {
        Long existingId = computeInTransaction(() -> {
            Announcement existing = announcements.save(Announcement.create("21026", null, null, "마이홈 공고",
                    AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING,
                    RecruitmentType.NEW, AgencyCode.LH, today().minusDays(30),
                    today().minusDays(20), today().minusDays(10), today().plusDays(10), "https://example.com/a", null,
                    0L, null));
            existing.enrichFromLh("P1", null, null);
            existing.recordAdminSupplyChange();
            SupplyRow corrected = SupplyRow.create(existing, complex, area59, "21026:1", 0, "동탄 행복주택", "공고문 59",
                    "1111010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 7);
            corrected.protectConditionsByAdmin();
            supplyRows.save(corrected);
            return existing.getId();
        });
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(30));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));

        refinement.refineAll();

        inTransaction(() -> {
            Announcement announcement = announcements.findById(existingId).orElseThrow();
            assertThat(announcement.isSupplyRecheckRequired()).isFalse();
            assertThat(supplyRows.findAllByAnnouncement(announcement)).singleElement().satisfies(row -> {
                assertThat(row.getSourceSupplyRowIdentifier()).isEqualTo("21026:1");
                assertThat(row.getSourceHousingTypeName()).isEqualTo("공고문 59");
                assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(7);
                assertThat(row.getAnnouncementComplex()).isNotNull();
            });
        });

        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "12", "30000000", "200000"));
        LhAnnouncementRefinementReport changed = refinement.refineAll();

        assertThat(changed.recheckRequiredAnnouncementCount()).isOne();
        inTransaction(() -> {
            Announcement announcement = announcements.findById(existingId).orElseThrow();
            assertThat(announcement.isSupplyRecheckRequired()).isTrue();
            assertThat(supplyRows.findAllByAnnouncement(announcement)).singleElement()
                    .satisfies(row -> assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(7));
        });
    }

    @Test
    void 정정공고는_원공고에_잇고_원공고의_같은_이름_공고_단지_연결을_이어받는다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(5));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        // 같은 공고일이고 공고 ID가 원공고보다 앞서도 원공고를 먼저 정제해 잇는다.
        publish("P0", "[정정공고] 동탄 행복주택 입주자 모집", today().minusDays(5));
        details("P0", "동탄 행복주택 단지", "주소 없음");
        supplies("P0", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report.linkedRevisionCount()).isOne();
        assertThat(report.announcementComplexes().inherited()).isOne();
        // 정정공고는 새 공고로 알리지 않는다. 원공고 하나만 알린다.
        verify(outbox, times(1)).appendNewAnnouncement(any(Announcement.class));
        inTransaction(() -> {
            Announcement revision = announcements.findAllByLhPanIdOrderByIdAsc("P0").getFirst();
            assertThat(revision.getStatus()).isEqualTo(AnnouncementPublicationType.CORRECTION);
            assertThat(revision.getPreviousAnnouncement().getLhPanId()).isEqualTo("P1");
            assertThat(announcementComplexes.findAllByAnnouncementOrderByIdAsc(revision)).singleElement()
                    .satisfies(found -> assertThat(found.linkedComplex().getId()).isEqualTo(complex.getId()));
        });
    }

    @Test
    void 관리자가_휴지통에_넣은_LH_공고는_되살리거나_새로_만들지_않는다() {
        computeInTransaction(() -> {
            Announcement trashed = announcements.save(Announcement.create("21026", null, null, "마이홈 공고",
                    AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING,
                    RecruitmentType.NEW, AgencyCode.LH, today().minusDays(30),
                    today().minusDays(20), today().minusDays(10), today().plusDays(10), "https://example.com/a", null,
                    0L, null));
            trashed.enrichFromLh("P1", null, null);
            trashed.moveToTrash();
            return trashed.getId();
        });
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report.createdAnnouncementCount()).isZero();
        assertThat(report.heldAnnouncementCounts()).containsEntry("TRASHED", 1);
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P1")).singleElement()
                .satisfies(found -> assertThat(found.isAdminDeleted()).isTrue());
    }

    private void publish(String panId, String name, LocalDate noticeDate) {
        catalogEntries.removeIf(entry -> entry.snapshot().panId().equals(panId));
        catalogEntries.add(new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", name, "공고중",
                noticeDate.format(CATALOG_DATE), noticeDate.format(CATALOG_DATE),
                today().plusDays(10).format(CATALOG_DATE), "https://apply.lh.or.kr/" + panId, ""), "{}"));
        catalog.store(List.copyOf(catalogEntries));
    }

    private void details(String panId, String complexName, String address) {
        String start = today().plusDays(1).format(COMPACT_DATE);
        String end = today().plusDays(3).format(COMPACT_DATE);
        String winner = today().plusDays(20).format(COMPACT_DATE);
        lhStorage.replaceDetails(panId, request(panId).requestDescription(), List.of(
                LhSourceViews.detail(0, panId, new LhAnnouncementDetailSourceSnapshot("COMPLEX", complexName, address,
                        null, "100", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null)),
                LhSourceViews.detail(1, panId, new LhAnnouncementDetailSourceSnapshot("SCHEDULE", complexName, null,
                        null, null, null, null, null, null, null, start, end, winner, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null))));
    }

    private void supplies(String panId, LhAnnouncementSupplySourceSnapshot... rows) {
        List<LhAnnouncementSupplySourceView> views = new ArrayList<>();
        for (int index = 0; index < rows.length; index++) {
            views.add(LhSourceViews.supply(index, panId, rows[index]));
        }
        lhStorage.replaceSupplies(panId, request(panId).requestDescription(), views);
    }

    private LhAnnouncementSupplySourceSnapshot supply(String complexName, String typeName, String area, String count,
            String deposit, String rent) {
        return new LhAnnouncementSupplySourceSnapshot(complexName, typeName, area, "80.0000", "100", count, deposit, rent);
    }

    private LhAnnouncementRequest request(String panId) {
        return new LhAnnouncementRequest(panId, "03", "06", "10", "063");
    }

    private HousingComplex complex(String name, String identifier, String roadAddress, String supplyType) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, supplyType,
                Address.create(roadAddress, "4159010100100010000", "4159010100", "41", "41590",
                        new BigDecimal("37.2"), new BigDecimal("127.1")),
                100, "LH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
    }

    private static LocalDate today() {
        return LocalDate.now();
    }

    private <T> T computeInTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private void inTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }
}
