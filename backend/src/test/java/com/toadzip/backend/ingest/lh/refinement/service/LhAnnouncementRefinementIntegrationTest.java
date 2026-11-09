package com.toadzip.backend.ingest.lh.refinement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.admin.exception.AdminDataStaleException;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AnnouncementOrigin;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.LhSupplyMatchStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyOwner;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.service.SupplyCorrectionService;
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
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lh.refinement.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.lh.refinement.dto.LhAnnouncementRefinementReport;
import com.toadzip.backend.ingest.lh.refinement.service.LhScheduleAttachmentWriter;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@ExtendWith(IngestExecutionExtension.class)
class LhAnnouncementRefinementIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String ADDRESS = "경기도 화성시 동탄대로 100";

    @Autowired private LhAnnouncementRefinementService refinement;
    @Autowired private SupplyCorrectionService supplyCorrections;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private LhStorageFixtures lhStorage;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementComplexRepository announcementComplexes;
    @Autowired private SupplyRowRepository supplyRows;
    @Autowired private SupplyTargetRepository supplyTargets;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private NotificationOutboxRepository outbox;
    @MockitoSpyBean private LhScheduleAttachmentWriter scheduleWriter;

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
    @WithMockUser(roles = "ADMIN")
    void 공고목록에만_있는_공고를_만들고_주소가_같은_단지에_자동_연결해_신청형마다_공급행을_만든다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A(청년)", "59.9800", "10", "30000000", "200000"),
                supply("동탄 행복주택 단지", "46A", "46.0000", "3", "20000000", "100000"),
                supply("동탄 행복주택 단지", "59A(고령자)", "59.9800", "4", "", ""));
        long complexCount = complexes.count();
        long typeCount = types.count();

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.failedAnnouncementCount()).isZero();
        assertThat(complexes.count()).isEqualTo(complexCount);
        // 정제는 주택형을 만들지 않는다. LH 면적은 함께 모집하는 면적 중 하나라 PDF 보정이 확인할 때 만든다.
        assertThat(types.count()).isEqualTo(typeCount);
        long id = computeInTransaction(() -> {
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
            assertThat(rows).extracting(SupplyRow::getSourceSupplyRowIdentifier).containsExactlyInAnyOrder(
                    "LHROW:" + announcementComplex.getId() + ":59a#1",
                    "LHROW:" + announcementComplex.getId() + ":46a#1");
            // 신청자 표기만 다른 LH 두 줄(59A(청년)·59A(고령자))은 같은 신청형이라 공급행 하나와 그 아래 대상 둘이 된다.
            SupplyRow area59Row = rows.stream().filter(row -> row.getSourceSupplyRowIdentifier().endsWith(":59a#1"))
                    .findFirst().orElseThrow();
            assertThat(area59Row.getSourceHousingTypeName()).isEqualTo("59A(청년) · 59A(고령자)");
            assertThat(area59Row.getTotalSupplyHouseholdCount()).isEqualTo(14);
            assertThat(area59Row.getHousingType().getId()).isEqualTo(area59.getId());
            assertThat(area59Row.getHousingTypes()).extracting(HousingType::getId).containsExactly(area59.getId());
            assertThat(area59Row.getSupplyPnu()).isNull();
            assertThat(area59Row.getSupplyCategory()).isEqualTo(SupplyCategory.NEW_SUPPLY);
            // 금액을 주지 않은 줄이 있으면 공고문으로 채워야 한다.
            assertThat(area59Row.getLhMatchStatus()).isEqualTo(LhSupplyMatchStatus.VALUES_MISSING);
            assertThat(supplyTargets.findAllBySupplyRow(area59Row)).extracting(SupplyTarget::getTarget,
                    SupplyTarget::getSupplyHouseholdCount, SupplyTarget::getSourceSupplyTargetIdentifier)
                    .containsExactly(tuple("청년", 10, "LH:P1:SUPPLY:0:TARGET"), tuple("고령자", 4, "LH:P1:SUPPLY:2:TARGET"));
            // 단지에 없는 대표 면적의 행은 주택형 없이 면적 미일치로 둔다. 다른 면적의 주택형을 고르지 않는다.
            SupplyRow unmatched = rows.stream().filter(row -> row != area59Row).findFirst().orElseThrow();
            assertThat(unmatched.getHousingType()).isNull();
            assertThat(unmatched.getHousingTypes()).isEmpty();
            assertThat(unmatched.getLhMatchStatus()).isEqualTo(LhSupplyMatchStatus.AREA_UNMATCHED);
            assertThat(supplyTargets.findAllBySupplyRow(unmatched)).extracting(SupplyTarget::getTarget)
                    .containsExactly("전체");
            return announcement.getId();
        });
        // 공고일이 7일 안인 새 공고만 새 공고 알림을 요청한다.
        verify(outbox).appendNewAnnouncement(any(Announcement.class));
        // LH 행 수(3)와 공급행 수(2)가 달라도 모든 LH 행이 같은 신청형 공급행에 붙어 '붙지 않은 LH 행'이 없다.
        boolean comparisonTables = ensureComparisonTables();
        try {
            var workspace = supplyCorrections.workspace(id);
            assertThat(workspace.unattachedLhSupplyRows()).isEmpty();
            assertThat(workspace.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::rowIdentifier,
                    SupplyCorrection.LhSupplyRow::typeName).containsExactly(tuple("LH:P1:SUPPLY:0", "59A(청년)"),
                    tuple("LH:P1:SUPPLY:1", "46A"), tuple("LH:P1:SUPPLY:2", "59A(고령자)"));
            String area59Key = workspace.lhSupplyRows().getFirst().attachedRowKey();
            assertThat(workspace.lhSupplyRows().getLast().attachedRowKey()).isEqualTo(area59Key);
            assertThat(workspace.rowSources()).filteredOn(source -> source.rowKey().equals(area59Key)).hasSize(2);
        } finally {
            if (comparisonTables) { jdbc.sql("DROP TABLE admin_comparison_checks, admin_comparison_sources").update(); }
        }
        List<Long> rowIds = supplyRowIds(id);

        LhAnnouncementRefinementReport again = refinement.refineAll();

        assertThat(again.createdAnnouncementCount()).isZero();
        assertThat(again.updatedAnnouncementCount()).isZero();
        assertThat(again.supplyRows().created()).isZero();
        assertThat(again.supplyRows().updated()).isZero();
        assertThat(again.supplyRows().deleted()).isZero();
        assertThat(again.supplyRows().createdTargets()).isZero();
        assertThat(again.supplyRows().updatedTargets()).isZero();
        assertThat(again.supplyRows().deletedTargets()).isZero();
        assertThat(again.announcementComplexes().created()).isZero();
        assertThat(again.announcementComplexes().updated()).isZero();
        assertThat(supplyRowIds(id)).isEqualTo(rowIds);
    }

    @Test
    void 같은_전용면적이어도_신청형이_다르면_공급행을_따로_두고_같은_주택형을_가리킨다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "84.9600A", "84.9600", "16", "30000000", "200000"),
                supply("동탄 행복주택 단지", "84.9600B", "84.96", "3", "30000000", "200000"),
                supply("동탄 행복주택 단지", "아파트", "", "5", "", ""),
                supply("동탄 행복주택 단지", "59A[청년]", "59.98", "2", "30000000", "200000"),
                supply("동탄 행복주택 단지", "59A [주거급여수급자]", "59.98", "1", "30000000", "200000"),
                supply("동탄 행복주택 단지", "59C(주거약자)", "59.98", "4", "30000000", "200000"));

        refinement.refineAll();

        inTransaction(() -> {
            Announcement announcement = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst();
            long complexId = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement).getFirst().getId();
            List<SupplyRow> rows = supplyRows.findAllByAnnouncement(announcement);
            assertThat(rows).extracting(SupplyRow::getSourceSupplyRowIdentifier, SupplyRow::getSourceHousingTypeName,
                    SupplyRow::getTotalSupplyHouseholdCount).containsExactlyInAnyOrder(
                    tuple("LHROW:" + complexId + ":84.9600a#1", "84.9600A", 16),
                    tuple("LHROW:" + complexId + ":84.9600b#1", "84.9600B", 3),
                    tuple("LHROW:" + complexId + ":아파트#1", "아파트", 5),
                    tuple("LHROW:" + complexId + ":59a#1", "59A[청년] · 59A [주거급여수급자]", 3),
                    tuple("LHROW:" + complexId + ":59c#1", "59C(주거약자)", 4));
            // 신청형이 다른 같은 면적의 행은 모두 그 면적의 단지 주택형을 가리킨다.
            assertThat(rows).filteredOn(row -> row.getSourceHousingTypeName().startsWith("84.9600"))
                    .extracting(row -> row.getHousingType().getName()).containsExactly("84A", "84A");
            assertThat(rows).filteredOn(row -> row.getSourceHousingTypeName().startsWith("59"))
                    .extracting(row -> row.getHousingType().getId()).containsOnly(area59.getId());
            // 신청자 표기([ ]도 신청자 표기다)만 다른 줄은 같은 신청형 공급행의 대상이고, 대상 이름은 그 신청자 표기다.
            SupplyRow area59Row = rows.stream().filter(row -> row.getSourceSupplyRowIdentifier().endsWith(":59a#1"))
                    .findFirst().orElseThrow();
            assertThat(supplyTargets.findAllBySupplyRow(area59Row)).extracting(SupplyTarget::getTarget,
                    SupplyTarget::getSupplyHouseholdCount).containsExactly(tuple("청년", 2), tuple("주거급여수급자", 1));
        });
    }

    @Test
    void LH_한_줄마다_만든_옛_공급행은_다시_정제하면_신청형_공급행으로_바뀐다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A(청년)", "59.9800", "10", "30000000", "200000"),
                supply("동탄 행복주택 단지", "59A(고령자)", "59.9800", "4", "30000000", "200000"));
        refinement.refineAll();
        long id = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst().getId();
        long complexId = jdbc.sql("SELECT id FROM announcement_complexes WHERE announcement_id = ?").param(id)
                .query(Long.class).single();
        // 이전 정제가 LH 줄마다 만든 공급행과 그 대상을 흉내 낸다.
        jdbc.sql("DELETE FROM supply_targets WHERE supply_row_id IN (SELECT id FROM supply_rows WHERE announcement_id = ?)")
                .param(id).update();
        jdbc.sql("DELETE FROM supply_row_housing_types WHERE supply_row_id IN (SELECT id FROM supply_rows WHERE announcement_id = ?)")
                .param(id).update();
        jdbc.sql("DELETE FROM supply_rows WHERE announcement_id = ?").param(id).update();
        for (var name : List.of("59A(청년)", "59A(고령자)")) {
            String key = "LHROW:" + complexId + ":" + name + "#1";
            jdbc.sql("""
                    INSERT INTO supply_rows (announcement_id, display_order, source_complex_name, source_housing_type_name,
                        source_supply_row_identifier, supply_category, housing_complex_id, housing_type_id, announcement_complex_id,
                        total_supply_household_count, admin_modified, admin_excluded, area_override, target_counts_comparable,
                        lh_total_supply_household_count_owned, lh_total_supply_household_count_enriched)
                    VALUES (?, 0, '동탄 행복주택 단지', ?, ?, 'NEW_SUPPLY', ?, ?, ?, 10, false, false, false, false, true, true)""")
                    .params(id, name, key, complex.getId(), area59.getId(), complexId).update();
            jdbc.sql("""
                    INSERT INTO supply_targets (supply_row_id, target, supply_rank, supply_household_count, display_order,
                        source_supply_target_identifier, admin_modified, admin_excluded)
                    SELECT id, '전체', '전체', 10, 1, ? , false, false FROM supply_rows WHERE source_supply_row_identifier = ?""")
                    .params(key + ":TARGET", key).update();
        }

        refinement.refineAll();

        inTransaction(() -> {
            var rows = supplyRows.findAllByAnnouncement(announcements.findById(id).orElseThrow());
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.getSourceSupplyRowIdentifier()).isEqualTo("LHROW:" + complexId + ":59a#1");
                assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(14);
                assertThat(supplyTargets.findAllBySupplyRow(row)).extracting(SupplyTarget::getTarget)
                        .containsExactly("청년", "고령자");
            });
        });
        assertThat(jdbc.sql("SELECT count(*) FROM supply_targets WHERE source_supply_target_identifier LIKE 'LHROW:%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void 공고_단지가_다르면_같은_전용면적이어도_공급행을_따로_둔다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"),
                supply("다른 단지", "59A", "59.9800", "7", "30000000", "200000"));

        refinement.refineAll();

        inTransaction(() -> {
            Announcement announcement = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst();
            assertThat(supplyRows.findAllByAnnouncement(announcement)).extracting(SupplyRow::getSourceComplexName,
                    SupplyRow::getTotalSupplyHouseholdCount).containsExactlyInAnyOrder(
                    tuple("동탄 행복주택 단지", 10), tuple("다른 단지", 7));
        });
    }

    @Test
    void 정제_보고서는_성공한_공고만_집계하고_실패한_공고의_중간_집계는_버린다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        // 공고를 만들고 공고 수를 센 뒤 실패해 그 공고만 되돌린다.
        publish("P2", "실패할 공고", today().minusDays(1));
        details("P2", "실패할 단지", ADDRESS);
        supplies("P2", supply("실패할 단지", "59A", "59.9800", "10", "30000000", "200000"));
        doThrow(new IllegalStateException("주입한 실패")).when(scheduleWriter)
                .write(argThat(announcement -> announcement.getName().equals("실패할 공고")), any(), any());
        publish("P3", "원천 없는 공고", today().minusDays(1));

        LhAnnouncementRefinementReport report = refinement.refineAll();

        assertThat(report).isEqualTo(new LhAnnouncementRefinementReport(3, 1, 0, 0, Map.of("SOURCES_MISSING", 1), 1,
                0, 0, 0, 0,
                new LhAnnouncementRefinementReport.ComplexCounts(1, 0, 0, 0, 0, 1, 0),
                new LhAnnouncementRefinementReport.SupplyCounts(1, 0, 0, 1, 0, 0, 0, Map.of("MATCHED", 1))));
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P2")).isEmpty();
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
    void 정정공고_묶음이_원공고_묶음보다_먼저_끝나도_마무리에서_원공고에_잇고_공고_단지_연결을_이어받는다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(5));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        publish("P0", "[정정공고] 동탄 행복주택 입주자 모집", today().minusDays(5));
        details("P0", "동탄 행복주택 단지", "주소 없음");
        supplies("P0", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        var run = refinement.begin();

        // 정정공고 묶음이 먼저 끝나면 이어 갈 원공고가 없다.
        run.refineBundle(List.of("P0"), List.of(sourceKeyOf("P0")));
        inTransaction(() -> assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P0").getFirst()
                .getPreviousAnnouncement()).isNull());
        run.refineBundle(List.of("P1"), List.of(sourceKeyOf("P1")));
        run.finish(java.time.Instant.MAX, List.of("P0", "P1"));

        var report = run.report();
        assertThat(report.linkedRevisionCount()).isOne();
        assertThat(report.unlinkedRevisionCount()).isZero();
        // 다시 정제한 정정공고를 공고 수로 두 번 세지 않는다.
        assertThat(report.targetCount()).isEqualTo(2);
        assertThat(report.createdAnnouncementCount()).isEqualTo(2);
        assertThat(report.announcementComplexes().inherited()).isOne();
        verify(outbox, times(1)).appendNewAnnouncement(any(Announcement.class));
        inTransaction(() -> assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P0").getFirst()
                .getPreviousAnnouncement().getLhPanId()).isEqualTo("P1"));
    }

    private String sourceKeyOf(String panId) {
        return jdbc.sql("SELECT source_key FROM lh_announcement_catalog_entries WHERE pan_id = ?").param(panId)
                .query(String.class).single();
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

    @Test
    @WithMockUser(roles = "ADMIN")
    void 정제_뒤_늘어난_다른_면적의_LH_공급_원천_행만_붙지_않은_LH_행으로_알리고_공고문_소유_공고는_알리지_않는다() throws Exception {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        refinement.refineAll();
        boolean comparisonTables = ensureComparisonTables();
        try {
            long id = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst().getId();
            assertThat(supplyCorrections.workspace(id).unattachedLhSupplyRows()).isEmpty();
            // 공급행의 원천 단지명이 공고 단지 이름이다. 화면은 이 주소로 PDF 단지를 공고 단지에 대응한다.
            assertThat(supplyCorrections.workspace(id).announcementComplexes()).singleElement().satisfies(view -> {
                assertThat(view.name()).isEqualTo("동탄 행복주택 단지");
                assertThat(view.roadAddress()).isEqualTo(ADDRESS);
            });

            supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"),
                    supply("동탄 행복주택 단지", "84A", "84.9600", "2", "40000000", "300000"));

            var workspace = supplyCorrections.workspace(id);
            assertThat(workspace.unattachedLhSupplyRows()).singleElement().satisfies(row -> {
                assertThat(row.rowIdentifier()).isEqualTo(LhSourceIdentifier.supply("P1", 1));
                assertThat(row.typeName()).isEqualTo("84A");
            });
            // 붙지 않은 LH 행은 정보일 뿐이다. 새 공급행을 추가하는 미리보기는 그 LH 행을 경고하지 않는다.
            var first = workspace.rows().getFirst();
            var added = new SupplyCorrection.Row(null, "new-84", first.housingComplexId(), first.housingComplexName(),
                    null, null, null, null, false, false, 9, first.complexName(), "84A", null, null, first.category(), 2,
                    false, null, null, List.of(), false, null);
            var request = new SupplyCorrection.Request(UUID.randomUUID(), workspace.snapshotToken(), "공고문 확인",
                    "https://example.com/notice", null, List.of(first, added));
            var preview = supplyCorrections.preview(id, request);
            assertThat(preview.warnings()).noneMatch(warning -> warning.contains("LH"));
            jdbc.sql("UPDATE announcements SET supply_owner = 'ANNOUNCEMENT_DOCUMENT' WHERE id = :id").param("id", id).update();
            assertThat(supplyCorrections.workspace(id).unattachedLhSupplyRows()).isEmpty();
        } finally {
            if (comparisonTables) { jdbc.sql("DROP TABLE admin_comparison_checks, admin_comparison_sources").update(); }
        }
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void 관리자가_자동_연결된_공고_단지를_다른_단지로_바꾸면_공급행도_같은_전용면적_주택형으로_옮기고_정제가_되돌리지_않는다() throws Exception {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"),
                supply("동탄 행복주택 단지", "84A", "84.9600", "2", "40000000", "300000"));
        publish("P2", "다른 행복주택 입주자 모집", today().minusDays(1));
        details("P2", "다른 행복주택 단지", "경기도 화성시 동탄대로 900");
        supplies("P2", supply("다른 행복주택 단지", "59A", "59.9800", "5", "30000000", "200000"));
        refinement.refineAll();
        HousingComplex other = complex("동탄 행복주택 2단지", "complex-2", "경기도 화성시 동탄대로 200", "HAPPY_HOUSING");
        HousingType other59 = types.save(HousingType.createFromMyHome(other, "complex-2:59", "59A", new BigDecimal("59.9800"), null));
        HousingType other84 = types.save(HousingType.createFromMyHome(other, "complex-2:84", "84A", new BigDecimal("84.9600"), null));
        boolean comparisonTables = jdbc.sql("SELECT to_regclass('admin_comparison_sources') IS NULL").query(Boolean.class).single();
        if (comparisonTables) {
            jdbc.sql(new String(new ClassPathResource("db/migration/V20261016_01__quality_comparison_workflow.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8)).update();
        }
        try {
            long id = announcements.findAllByLhPanIdOrderByIdAsc("P1").getFirst().getId();
            var before = supplyCorrections.workspace(id);
            var view = before.announcementComplexes().getFirst();
            assertThat(view.linkMethod()).isEqualTo("AUTO");
            assertThat(view.housingComplexId()).isEqualTo(complex.getId());
            assertThat(view.rowCount()).isEqualTo(2);

            // 같은 단지로 확인만 하면 공급행의 주택형 연결은 그대로 둔다. 같은 면적 주택형이 둘이 되어 면적으로 다시 고르면 지워질 행이다.
            types.save(HousingType.createFromMyHome(complex, "complex-1:59B", "59B", new BigDecimal("59.9800"), null));
            var confirmed = supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest(before.snapshotToken(), complex.getId()), "operator");
            assertThat(confirmed.announcementComplexes().getFirst().linkMethod()).isEqualTo("ADMIN");
            assertThat(confirmed.rows()).extracting(SupplyCorrection.Row::housingTypeId).contains(area59.getId());
            // 공급행이 그대로여도 연결이 바뀌었으므로 확인 전에 받은 토큰으로는 더 바꾸지 못한다.
            assertThatThrownBy(() -> supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest(before.snapshotToken(), other.getId()), "operator")).isInstanceOf(AdminDataStaleException.class);
            HousingComplex trashed = complex("휴지통 단지", "complex-3", "경기도 화성시 동탄대로 300", "HAPPY_HOUSING");
            jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id").param("id", trashed.getId()).update();
            assertThatThrownBy(() -> supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest(confirmed.snapshotToken(), trashed.getId()), "operator"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("휴지통");

            assertThatThrownBy(() -> supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest("stale", other.getId()), "operator")).isInstanceOf(AdminDataStaleException.class);
            var after = supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest(confirmed.snapshotToken(), other.getId()), "operator");

            assertThat(after.announcementComplexes().getFirst()).satisfies(changed -> {
                assertThat(changed.linkMethod()).isEqualTo("ADMIN");
                assertThat(changed.housingComplexId()).isEqualTo(other.getId());
                assertThat(changed.housingComplexName()).isEqualTo("동탄 행복주택 2단지");
            });
            assertThat(after.rows()).extracting(SupplyCorrection.Row::housingComplexId, SupplyCorrection.Row::housingTypeId)
                    .containsExactlyInAnyOrder(tuple(other.getId(), other59.getId()), tuple(other.getId(), other84.getId()));
            // 확인 한 번, 변경 한 번. 변경 이력에는 옮긴 공급행의 이전·이후 단지도 남는다.
            var history = jdbc.sql("SELECT before_value, after_value FROM admin_data_changes WHERE action = 'LINK_BY_ADMIN' AND actor = 'operator' "
                    + "ORDER BY id").query((rs, index) -> List.of(rs.getString(1), rs.getString(2))).list();
            assertThat(history).hasSize(2);
            assertThat(history.get(1).get(0)).contains("\"housingComplexId\":" + complex.getId()).contains("\"rows\":[{");
            assertThat(history.get(1).get(1)).contains("\"housingTypeId\":" + other59.getId());
            // 연결만 바꿨으므로 공급정보는 계속 LH 원천 소유이고, 다시 정제해도 관리자 연결과 옮긴 공급행이 유지된다.
            assertThat(announcements.findById(id).orElseThrow().suppliedByLh()).isTrue();
            refinement.refineAll();
            assertThat(supplyCorrections.workspace(id).rows()).extracting(SupplyCorrection.Row::housingComplexId)
                    .containsOnly(other.getId());

            long foreign = announcementComplexes.findAll().stream().filter(item -> !item.getAnnouncement().getId().equals(id))
                    .findFirst().orElseThrow().getId();
            var latest = supplyCorrections.workspace(id).snapshotToken();
            assertThatThrownBy(() -> supplyCorrections.linkAnnouncementComplex(id, foreign,
                    new SupplyCorrection.ComplexLinkRequest(latest, complex.getId()), "operator"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이 공고의 공고 단지가 아닙니다");

            // 공통 모집 묶음 행은 묶음째 옮길 수 없으므로 거절하고 연결을 바꾸지 않는다.
            long group = jdbc.sql("""
                    INSERT INTO supply_row_groups(version, announcement_id, source_row_identifier, source_fingerprint, source_url, reason, actor,
                        updated_at, review_required)
                    VALUES (0, :id, 'GROUP', 'f', 'https://example.com', '묶음', 'operator', CURRENT_TIMESTAMP, false) RETURNING id
                    """).param("id", id).query(Long.class).single();
            jdbc.sql("UPDATE supply_rows SET supply_group_id = :group, total_supply_household_count = NULL WHERE announcement_id = :id")
                    .param("group", group).param("id", id).update();
            var grouped = supplyCorrections.workspace(id).snapshotToken();
            assertThatThrownBy(() -> supplyCorrections.linkAnnouncementComplex(id, view.id(),
                    new SupplyCorrection.ComplexLinkRequest(grouped, complex.getId()), "operator"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공통 모집 묶음");
            assertThat(supplyCorrections.workspace(id).announcementComplexes().getFirst().housingComplexId()).isEqualTo(other.getId());
        } finally {
            if (comparisonTables) { jdbc.sql("DROP TABLE admin_comparison_checks, admin_comparison_sources").update(); }
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자가_고친_LH_공급행도_원천_순번이_바뀌면_다시_정제할_때_LH_행_대응을_옮긴다() throws Exception {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        supplies("P1", supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));
        refinement.refineAll();
        jdbc.sql("UPDATE supply_rows SET admin_modified = true, total_supply_household_count = 7").update();
        supplies("P1", supply("동탄 행복주택 단지", "84A", "84.9600", "2", "40000000", "300000"),
                supply("동탄 행복주택 단지", "59A", "59.9800", "10", "30000000", "200000"));

        refinement.refineAll();

        inTransaction(() -> {
            SupplyRow corrected = supplyRows.findAllByAnnouncement(announcements.findAllByLhPanIdOrderByIdAsc("P1")
                    .getFirst()).stream().filter(row -> row.getSourceHousingTypeName().equals("59A")).findFirst().orElseThrow();
            assertThat(corrected.getLhSourceSupplyRowIdentifier()).isEqualTo(LhSourceIdentifier.supply("P1", 1));
            assertThat(corrected.getTotalSupplyHouseholdCount()).isEqualTo(7);
        });
    }

    @Test
    void 접수_시작일이_별도안내인_신규공고는_공고일로_대체하지_않고_보류하며_일정_확인후_등록한다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS, "별도 안내");

        var held = refinement.refineAll();
        assertThat(held.failedAnnouncementCount()).isZero();
        assertThat(held.heldAnnouncementCounts()).containsEntry("VALUES_MISSING", 1);
        assertThat(announcements.count()).isZero();
        assertThat(supplyRows.count()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox_events").query(Long.class).single()).isZero();
        verify(outbox, never()).appendNewAnnouncement(any());

        details("P1", "동탄 행복주택 단지", ADDRESS);
        assertThat(refinement.refineAll().createdAnnouncementCount()).isOne();
        assertThat(announcements.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getApplicationStartDate()).isEqualTo(today().plusDays(1)));
    }

    @Test
    void 기존_공고는_원천_접수시작일이_없어져도_이미_확인한_시작일을_공고일로_바꾸지_않는다() {
        publish("P1", "동탄 행복주택 입주자 모집", today().minusDays(1));
        details("P1", "동탄 행복주택 단지", ADDRESS);
        refinement.refineAll();
        details("P1", "동탄 행복주택 단지", ADDRESS, "별도 안내");
        assertThat(refinement.refineAll().failedAnnouncementCount()).isZero();
        assertThat(announcements.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getApplicationStartDate()).isEqualTo(today().plusDays(1)));
    }

    private void publish(String panId, String name, LocalDate noticeDate) {
        catalogEntries.removeIf(entry -> entry.snapshot().panId().equals(panId));
        catalogEntries.add(new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", name, "공고중",
                noticeDate.format(CATALOG_DATE), noticeDate.format(CATALOG_DATE),
                today().plusDays(10).format(CATALOG_DATE), "https://apply.lh.or.kr/" + panId, ""), "{}"));
        catalog.store(List.copyOf(catalogEntries));
    }

    private void details(String panId, String complexName, String address) {
        details(panId, complexName, address, today().plusDays(1).format(COMPACT_DATE));
    }

    private void details(String panId, String complexName, String address, String start) {
        String end = today().plusDays(3).format(COMPACT_DATE);
        if ("별도 안내".equals(start)) {
            end = "별도 안내";
        }
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

    /** 공급 보정 작업실은 비교 검토 테이블을 읽는다. 이 테스트 스키마에는 없어 테스트 동안만 만든다. 만들었으면 true다. */
    private boolean ensureComparisonTables() {
        boolean missing = jdbc.sql("SELECT to_regclass('admin_comparison_sources') IS NULL").query(Boolean.class).single();
        if (missing) {
            try {
                jdbc.sql(new String(new ClassPathResource("db/migration/V20261016_01__quality_comparison_workflow.sql")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8)).update();
            }
            catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        return missing;
    }

    private List<Long> supplyRowIds(long announcementId) {
        return jdbc.sql("SELECT id FROM supply_rows WHERE announcement_id = ? ORDER BY id").param(announcementId)
                .query(Long.class).list();
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
