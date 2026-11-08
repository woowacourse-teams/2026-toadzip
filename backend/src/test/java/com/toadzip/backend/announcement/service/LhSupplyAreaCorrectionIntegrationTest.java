package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.LinkedHousingType;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.LhSourceViews;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.lh.refinement.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.lh.refinement.service.LhScheduleAttachmentWriter;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementRefinementService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 공급행은 (공고 단지, 전용면적 주택형) 하나이고 LH 공급 원본 행은 같은 면적 공급행에 붙는 보조 원천이다. LH 행 수와 공급행 수가 달라도
 * 오류가 아니고, PDF 보정은 LH 행을 나누거나 붙이지 않고 공급행을 고친다. LH 공고 정제로 만든 LH 전용 공고에서 확인한다.
 */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@ExtendWith(IngestExecutionExtension.class)
@WithMockUser(username = "operator", roles = "ADMIN")
class LhSupplyAreaCorrectionIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String COMPLEX_NAME = "대구테크노폴리스4";
    private static final String ADDRESS = "대구광역시 달성군 현풍읍 테크노대로 100";
    private static final String PAN_ID = "P747";
    private static final String OTHER_COMPLEX = "대구옥포";
    private static final String OTHER_ADDRESS = "대구광역시 달성군 옥포읍 강림로 100";

    @Autowired private LhAnnouncementRefinementService refinement;
    @Autowired private SupplyCorrectionService supplyCorrections;
    @Autowired private AnnouncementJsonCorrectionService jsonCorrections;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private LhStorageFixtures lhStorage;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @Autowired private PdfExtractionInputs extractionInputs;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private NotificationOutboxRepository outbox;
    @MockitoSpyBean private LhScheduleAttachmentWriter scheduleWriter;

    private final List<Entry> catalogEntries = new ArrayList<>();
    private boolean comparisonTables;
    private boolean otherComplex;
    private HousingComplex complex;
    private HousingType type5198;
    private HousingType type5991;
    private HousingType type5993;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, lh_announcement_catalog_entries,
                    lh_announcement_query_sources, admin_data_changes, notification_outbox_events CASCADE
                """).update();
        // 공급 보정 작업실은 비교 검토 테이블을 읽는다. 이 테스트 스키마에는 없어 이 테스트 동안만 만든다.
        comparisonTables = jdbc.sql("SELECT to_regclass('admin_comparison_sources') IS NULL").query(Boolean.class).single();
        if (comparisonTables) {
            jdbc.sql(new String(new ClassPathResource("db/migration/V20261016_01__quality_comparison_workflow.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8)).update();
        }
        complex = complexes.save(HousingComplex.createFromMyHome(COMPLEX_NAME, "complex-747", "HAPPY_HOUSING",
                Address.create(ADDRESS, "2771025021100010000", "2771025021", "27", "27710",
                        new BigDecimal("35.69"), new BigDecimal("128.45")),
                1000, "LH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
        type5198 = types.save(HousingType.createFromMyHome(complex, "complex-747:51", "051.9800A", new BigDecimal("51.9800"), null));
        type5991 = types.save(HousingType.createFromMyHome(complex, "complex-747:5991", "059.9100A", new BigDecimal("59.9100"), null));
        type5993 = types.save(HousingType.createFromMyHome(complex, "complex-747:5993", "059.9300B", new BigDecimal("59.9300"), null));
    }

    @AfterEach
    void dropComparisonTables() {
        if (comparisonTables) { jdbc.sql("DROP TABLE admin_comparison_checks, admin_comparison_sources").update(); }
    }

    @Test
    void LH가_신청자_묶음마다_나눈_세_줄은_공급행_하나에_붙고_PDF_보정은_대상별_모집수로_그_행을_고친다() throws Exception {
        var type36 = types.save(HousingType.createFromMyHome(complex, "complex-747:36", "36A", new BigDecimal("36.8500"), null));
        long id = refinedAnnouncement(supply("36A형(고령자)", "36.8500", "62"), supply("36A형(신혼부부·한부모가족)", "36.85", "388"),
                supply("36A형(청년)", "36.8500", "116"));
        var workspace = jsonCorrections.workspace(id);
        var row = workspace.supply().rows().getFirst();
        assertThat(workspace.supply().rows()).hasSize(1);
        assertThat(row.housingTypeId()).isEqualTo(type36.getId());
        assertThat(row.householdCount()).isEqualTo(566);
        assertThat(workspace.supply().unattachedLhSupplyRows()).isEmpty();
        assertThat(workspace.supply().lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::attachedRowKey,
                SupplyCorrection.LhSupplyRow::ambiguous).containsOnly(tuple(row.key(), false));
        // 공고문이 고령자 모집수를 60으로 적었다. LH 합(566)과 달라도 대상별 모집수로 저장한다.
        var targets = row.targets().stream().map(target -> target.target().equals("고령자") ? new SupplyCorrection.Target(
                target.id(), target.key(), false, true, target.displayOrder(), target.target(), target.supplyRank(), 60,
                target.reserveCount(), target.deposit(), target.rent(), target.convertedDeposit(), target.condition()) : target)
                .toList();
        var edited = new Row(row.id(), row.key(), row.housingComplexId(), row.housingComplexName(), row.housingTypeId(), null,
                null, null, false, false, row.displayOrder(), row.complexName(), "36A", null, row.moveInMonth(), row.category(),
                564, false, null, null, targets, true, List.of());
        var request = jsonRequest(workspace, List.of(edited));

        var preview = jsonCorrections.preview(id, request);
        jsonCorrections.apply(id, new AnnouncementJsonCorrection.Apply(request, preview.confirmationToken()), "operator");

        assertThat(preview.supply().warnings()).noneMatch(warning -> warning.contains("LH"));
        var after = supplyCorrections.workspace(id);
        assertThat(after.rows()).singleElement().satisfies(saved -> {
            assertThat(saved.householdCount()).isEqualTo(564);
            assertThat(saved.targets()).extracting(SupplyCorrection.Target::target, SupplyCorrection.Target::householdCount)
                    .containsExactly(tuple("고령자", 60), tuple("신혼부부·한부모가족", 388), tuple("청년", 116));
        });
        assertThat(after.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::attachedRowKey).containsOnly(row.key());
    }

    @Test
    void 신청형이_다른_행은_같은_주택형을_함께_가리키고_같은_신청형의_새_행은_거절한다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9300B", "59.9300", "15"));
        var workspace = supplyCorrections.workspace(id);
        var existing = workspace.rows().stream().filter(row -> type5991.getId().equals(row.housingTypeId())).findFirst()
                .orElseThrow();
        var rows = new ArrayList<>(workspace.rows());
        rows.add(edit(existing, null, "new-59", "59.9100A(고령자)", type5991, 25));

        rejects(id, workspace, rows, "같은 주택형(신청형)입니다");

        rows.removeLast();
        rows.add(edit(existing, null, "new-59c", "059.9100C(주거약자)", type5991, 4));
        applySupply(id, request(workspace, rows));
        assertThat(supplyCorrections.workspace(id).rows()).filteredOn(row -> !row.excluded())
                .extracting(Row::typeName, Row::housingTypeId).containsExactlyInAnyOrder(tuple("059.9100A", type5991.getId()),
                        tuple("059.9100C(주거약자)", type5991.getId()), tuple("059.9300B", type5993.getId()));
        // 같은 면적 공급행이 둘이어도 LH 줄은 신청형이 같은 행에만 붙고 모호하지 않다.
        assertThat(supplyCorrections.workspace(id).lhSupplyRows()).filteredOn(lh -> lh.typeName().equals("059.9100A"))
                .extracting(SupplyCorrection.LhSupplyRow::attachedRowKey, SupplyCorrection.LhSupplyRow::ambiguous)
                .containsExactly(tuple(existing.key(), false));
    }

    @Test
    void 정제가_같은_주택타입에_붙인_신청형이_다른_LH_행은_한_행만_고쳐도_저장되고_같은_신청형의_새_행은_거절한다() {
        // 두 LH 행은 모두 주택타입 059.9100A(type5991)를 가리켜 작업실 이름이 둘 다 '059.9100A'로 보인다.
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9100C(주거약자)", "59.9100", "4"));
        var workspace = supplyCorrections.workspace(id);
        assertThat(workspace.rows()).extracting(Row::typeName, Row::housingTypeId)
                .containsOnly(tuple("059.9100A", type5991.getId()));
        var weak = workspace.rows().stream().filter(row -> row.householdCount() == 4).findFirst().orElseThrow();
        var strong = workspace.rows().stream().filter(row -> row.householdCount() == 25).findFirst().orElseThrow();
        // 작업실은 행 이름과 따로 행별 신청형 이름(원천 형명)을 준다. 화면은 이 이름으로 같은 신청형을 가린다.
        assertThat(workspace.applicationTypeNames()).containsEntry(strong.key(), "059.9100A")
                .containsEntry(weak.key(), "059.9100C(주거약자)");
        // LH 줄은 각자 원천 형명이 같은 행에 모호하지 않게 붙는다.
        assertThat(workspace.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::typeName,
                SupplyCorrection.LhSupplyRow::attachedRowKey, SupplyCorrection.LhSupplyRow::ambiguous)
                .containsExactlyInAnyOrder(tuple("059.9100A", strong.key(), false), tuple("059.9100C(주거약자)", weak.key(), false));

        var duplicated = new ArrayList<>(workspace.rows());
        duplicated.add(edit(strong, null, "new-59c", "059.9100C", type5991, 4));
        rejects(id, workspace, duplicated, "같은 주택형(신청형)입니다");

        var rows = workspace.rows().stream().map(row -> row.key().equals(weak.key())
                ? edit(row, row.id(), row.key(), row.typeName(), type5991, 5) : row).toList();
        applySupply(id, request(workspace, rows));
        assertThat(jdbc.sql("SELECT total_supply_household_count FROM supply_rows WHERE id = :id").param("id", weak.id())
                .query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void 다시_보정하지_않은_옛_공급행이_같은_면적으로_여럿이면_LH_행은_첫_행에만_붙고_손대지_않은_옛_행은_저장을_막지_않는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9300B", "59.9300", "15"));
        long first = jdbc.sql("SELECT id FROM supply_rows WHERE announcement_id = :id AND housing_type_id = :type")
                .param("id", id).param("type", type5991.getId()).query(Long.class).single();
        // 신청자 묶음마다 나뉘어 저장된 옛 단위 공급행을 흉내 낸다. 같은 주택형을 가리키는 행이 둘이다.
        jdbc.sql("""
                INSERT INTO supply_rows (announcement_id, display_order, source_complex_name, source_housing_type_name,
                    source_supply_row_identifier, supply_category, housing_complex_id, housing_type_id, announcement_complex_id,
                    lh_exclusive_area, admin_modified, admin_excluded, area_override, target_counts_comparable,
                    lh_total_supply_household_count_owned, lh_total_supply_household_count_enriched)
                SELECT announcement_id, 9, source_complex_name, '059.9100A(고령자)', 'OLD:copy', supply_category,
                    housing_complex_id, housing_type_id, announcement_complex_id, lh_exclusive_area, true, false, false, false,
                    false, false
                FROM supply_rows WHERE id = :id""").param("id", first).update();
        jdbc.sql("""
                INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id)
                SELECT id, housing_type_id FROM supply_rows WHERE source_supply_row_identifier = 'OLD:copy'""").update();

        var workspace = supplyCorrections.workspace(id);

        assertThat(workspace.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::typeName,
                SupplyCorrection.LhSupplyRow::attachedRowKey, SupplyCorrection.LhSupplyRow::ambiguous)
                .contains(tuple("059.9100A", "row:" + first, true));
        var other = workspace.rows().stream().filter(row -> type5993.getId().equals(row.housingTypeId())).findFirst()
                .orElseThrow();
        var rows = workspace.rows().stream().map(row -> row.key().equals(other.key())
                ? edit(row, row.id(), row.key(), row.typeName(), type5993, 14) : row).toList();
        applySupply(id, request(workspace, rows));
        assertThat(jdbc.sql("SELECT total_supply_household_count FROM supply_rows WHERE id = :id").param("id", other.id())
                .query(Integer.class).single()).isEqualTo(14);
    }

    @Test
    void 주택형을_정하지_않고_전용면적만_준_행은_서버가_같은_면적_주택형을_고르거나_새로_만든다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = supplyCorrections.workspace(id);
        var base = workspace.rows().getFirst();
        // 둘째 자리까지 같으면 같은 주택형이다(59.931 → 59.93).
        var sameArea = areaRow(base, "pdf-59b", "59B", new BigDecimal("59.931"), 15, 1);
        // 공고 표기를 이어 적은 행이라도 단지 주택형 이름은 첫 표기에서 신청자 구분을 뗀 이름이다.
        var newArea = areaRow(base, "pdf-84", "84A(고령자)·84B", new BigDecimal("84.96"), 7, 2);
        var request = request(workspace, List.of(base, sameArea, newArea));

        var preview = supplyCorrections.preview(id, request);

        assertThat(preview.after()).filteredOn(row -> row.key().equals("pdf-59b")).singleElement()
                .satisfies(row -> assertThat(row.housingTypeId()).isEqualTo(type5993.getId()));
        assertThat(preview.after()).filteredOn(row -> row.key().equals("pdf-84")).singleElement().satisfies(row -> {
            assertThat(row.housingTypeId()).isNull();
            assertThat(row.newHousingType().name()).isEqualTo("84A");
        });
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
        assertThat(jdbc.sql("SELECT count(*) FROM housing_types WHERE housing_complex_id = :id AND exclusive_area = 84.96")
                .param("id", complex.getId()).query(Long.class).single()).isOne();
    }

    @Test
    void 주택형이_없던_LH_공급행에_PDF_면적을_주면_새_주택형을_만들어_연결하고_연결을_끊은_요청은_다시_채우지_않는다() {
        long id = refinedAnnouncement(supply("084.9600A", "84.9600", "7"));
        var workspace = supplyCorrections.workspace(id);
        var row = workspace.rows().getFirst();
        assertThat(row.housingTypeId()).isNull();
        var withArea = new Row(row.id(), row.key(), row.housingComplexId(), row.housingComplexName(), null, null, null, null,
                false, false, row.displayOrder(), row.complexName(), "84A", null, row.moveInMonth(), row.category(), 7, false,
                new BigDecimal("84.96"), null, row.targets(), false, List.of());

        applySupply(id, request(workspace, List.of(withArea)));

        var linked = supplyCorrections.workspace(id);
        Long created = linked.rows().getFirst().housingTypeId();
        assertThat(created).isNotNull();
        assertThat(types.findById(created).orElseThrow().getName()).isEqualTo("84A");
        var cleared = linked.rows().getFirst().withHousingType(null, null);
        applySupply(id, request(linked, List.of(cleared)));
        assertThat(supplyCorrections.workspace(id).rows().getFirst().housingTypeId()).isNull();
    }

    @Test
    void 다른_행만_고친_저장은_표시_순서가_밀려도_손대지_않은_주택형_없는_행과_제외한_행에_주택형을_만들지_않는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("084.9600A(고령자)", "84.9600", "7"),
                supply("084.9600A(청년)", "84.9600", "3"));
        // 신청자 묶음마다 나뉜 주택형 없는 옛 행 둘을 흉내 낸다(같은 면적).
        long typeless = jdbc.sql("SELECT id FROM supply_rows WHERE announcement_id = :id AND housing_type_id IS NULL")
                .param("id", id).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO supply_rows (announcement_id, display_order, source_complex_name, source_housing_type_name,
                    source_supply_row_identifier, supply_category, housing_complex_id, announcement_complex_id, lh_exclusive_area,
                    admin_modified, admin_excluded, area_override, target_counts_comparable,
                    lh_total_supply_household_count_owned, lh_total_supply_household_count_enriched)
                SELECT announcement_id, 9, source_complex_name, '084.9600A(청년)', 'OLD:typeless', supply_category,
                    housing_complex_id, announcement_complex_id, lh_exclusive_area, false, true, false, false, false, false
                FROM supply_rows WHERE id = :id""").param("id", typeless).update();
        long types = jdbc.sql("SELECT count(*) FROM housing_types").query(Long.class).single();
        var workspace = supplyCorrections.workspace(id);
        var rows = workspace.rows().stream().map(row -> type5991.getId().equals(row.housingTypeId())
                ? edit(row, row.id(), row.key(), row.typeName(), type5991, 24) : row.withLinkedHousingTypes(null)).toList();
        // 제외한 옛 행은 면적을 보내도 주택형을 정하지 않는다.
        rows = rows.stream().map(row -> row.excluded() ? new Row(row.id(), row.key(), row.housingComplexId(),
                row.housingComplexName(), null, null, row.groupId(), row.sharedHouseholdCount(), row.protectedValues(),
                true, row.displayOrder(), row.complexName(), row.typeName(), row.pnu(), row.moveInMonth(),
                row.category(), row.householdCount(), row.areaOverride(), new BigDecimal("84.96"), row.supplyArea(),
                row.targets(), row.targetCountsComparable(), null) : row).toList();

        // PDF 보정처럼 새 행을 맨 앞(표시 순서 0)에 넣어 나머지 행의 표시 순서가 밀려도 손대지 않은 행으로 본다.
        var base = rows.getFirst();
        var shifted = new ArrayList<Row>();
        shifted.add(areaRow(base, "pdf-first", "39A", new BigDecimal("39.39"), 4, 0));
        rows.forEach(row -> shifted.add(row.withDisplayOrder(row.displayOrder() + 1)));
        rows = shifted;

        applySupply(id, request(workspace, rows));

        assertThat(jdbc.sql("SELECT count(*) FROM housing_types").query(Long.class).single()).isEqualTo(types + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM supply_rows WHERE announcement_id = :id AND housing_type_id IS NULL")
                .param("id", id).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT admin_modified FROM supply_rows WHERE id = :id").param("id", typeless)
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void 신청형이_다른_두_새_행은_같은_면적이어도_이름별로_새_주택타입을_따로_만들고_같은_신청형_행은_관리자_추가_행을_지우면_저장한다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = supplyCorrections.workspace(id);
        var base = workspace.rows().getFirst();

        applySupply(id, request(workspace, List.of(base, areaRow(base, "a", "84A", new BigDecimal("84.96"), 7, 1),
                areaRow(base, "b", "84A-1", new BigDecimal("84.964"), 3, 2))));
        var saved = supplyCorrections.workspace(id);
        // 단지 주택타입은 공고문 표기 이름으로 나눈다. 84A와 84A-1은 전용면적이 같아도 다른 주택타입이다.
        assertThat(saved.rows()).filteredOn(row -> row.typeName().startsWith("84A")).extracting(Row::housingTypeId)
                .doesNotContainNull().doesNotHaveDuplicates().hasSize(2);
        assertThat(jdbc.sql("SELECT name FROM housing_types WHERE housing_complex_id = :complex AND exclusive_area BETWEEN 84.955 AND 84.965")
                .param("complex", complex.getId()).query(String.class).list()).containsExactlyInAnyOrder("84A", "84A-1");

        var manual = saved.rows().stream().filter(row -> row.typeName().equals("84A")).findFirst().orElseThrow();
        var replacement = areaRow(base, "c", "84A형", new BigDecimal("84.96"), 8, 3);
        var duplicated = new ArrayList<>(saved.rows());
        duplicated.add(replacement);
        rejects(id, saved, duplicated, "같은 주택형(신청형)입니다");

        var rows = new ArrayList<>(saved.rows().stream().filter(row -> !row.key().equals(manual.key())).toList());
        rows.add(replacement);
        var request = new SupplyCorrection.Request(UUID.randomUUID(), saved.snapshotToken(), "공고문 공급 표 대조",
                "https://apply.lh.or.kr/notice", "3", rows, List.of(manual.key()));
        var preview = supplyCorrections.preview(id, request);
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
        assertThat(supplyCorrections.workspace(id).rows()).extracting(Row::typeName)
                .containsExactlyInAnyOrder("059.9100A", "84A-1", "84A형");
    }

    @Test
    void 공고문이_두_면적을_한_공급행으로_묶으면_다른_면적의_LH_줄도_그_행에_붙는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9300B", "59.9300", "15"));
        var workspace = supplyCorrections.workspace(id);
        var first = workspace.rows().stream().filter(row -> type5991.getId().equals(row.housingTypeId())).findFirst().orElseThrow();
        var second = workspace.rows().stream().filter(row -> type5993.getId().equals(row.housingTypeId())).findFirst().orElseThrow();
        // 공고문이 59A/B를 모집수 40으로만 적었다. 두 면적을 한 공급행에 연결하고 나머지 행은 제외한다.
        var merged = new Row(first.id(), first.key(), complex.getId(), first.housingComplexName(), type5991.getId(), null, null,
                null, false, false, first.displayOrder(), first.complexName(), "59A/B", null, first.moveInMonth(),
                first.category(), 40, false, null, null, first.targets(), false,
                List.of(new LinkedHousingType(type5993.getId(), null)));

        applySupply(id, request(workspace, List.of(merged, excluded(second))));

        assertThat(supplyCorrections.workspace(id).lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::typeName,
                SupplyCorrection.LhSupplyRow::attachedRowKey, SupplyCorrection.LhSupplyRow::ambiguous)
                .containsExactly(tuple("059.9100A", first.key(), false), tuple("059.9300B", first.key(), false));
    }

    @Test
    void 옛_공통_모집_묶음은_다시_보정할_때_한_행이_빠지고_나머지를_제외하면_그_행_하나가_모집수를_갖는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9300B", "59.9300", "15"));
        // 예전 PDF 보정이 59A·59B를 공통 모집수 40으로 묶어 둔 상태를 흉내 낸다.
        long groupId = jdbc.sql("""
                INSERT INTO supply_row_groups (announcement_id, source_row_identifier, shared_household_count, source_fingerprint,
                    source_url, reason, actor, updated_at, review_required, version)
                VALUES (:id, 'old-group', 40, 'fingerprint', 'https://example.com', '이전 보정', 'admin', now(), false, 0)
                RETURNING id""").param("id", id).query(Long.class).single();
        jdbc.sql("UPDATE supply_rows SET supply_group_id = :group, total_supply_household_count = NULL, admin_modified = true "
                + "WHERE announcement_id = :id").param("group", groupId).param("id", id).update();
        var workspace = supplyCorrections.workspace(id);
        var first = workspace.rows().stream().filter(row -> type5991.getId().equals(row.housingTypeId())).findFirst().orElseThrow();
        var second = workspace.rows().stream().filter(row -> type5993.getId().equals(row.housingTypeId())).findFirst().orElseThrow();
        var merged = new Row(first.id(), first.key(), complex.getId(), first.housingComplexName(), type5991.getId(), null, null,
                null, false, false, first.displayOrder(), first.complexName(), "59A/B", null, first.moveInMonth(),
                first.category(), 40, false, null, null, first.targets(), false,
                List.of(new LinkedHousingType(type5993.getId(), null)));

        rejects(id, workspace, List.of(merged, second), "이 행도 묶음에서 빼거나 제외하세요");
        applySupply(id, request(workspace, List.of(merged, excluded(second))));

        assertThat(jdbc.sql("SELECT coalesce(supply_group_id, 0), coalesce(total_supply_household_count, -1), admin_excluded "
                + "FROM supply_rows WHERE id = :id").param("id", first.id())
                .query((rs, index) -> List.of(rs.getLong(1), rs.getInt(2), rs.getBoolean(3))).single())
                .isEqualTo(List.of(0L, 40, false));
        assertThat(jdbc.sql("SELECT admin_excluded FROM supply_rows WHERE id = :id").param("id", second.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void 면적이_없는_LH_줄은_그_줄로_만든_공급행에_붙는다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));

        var workspace = supplyCorrections.workspace(id);

        assertThat(workspace.lhSupplyRows()).singleElement()
                .satisfies(lh -> assertThat(lh.attachedRowKey()).isEqualTo(workspace.rows().getFirst().key()));
        assertThat(workspace.unattachedLhSupplyRows()).isEmpty();
    }

    @Test
    void 여러_단지_공고에서_LH_줄은_자기_공고_단지의_같은_면적_공급행에만_붙는다() {
        otherComplex = true;
        publish(PAN_ID);
        details(PAN_ID);
        supplies(supply("059.9100A", "59.9100", "25"), new LhAnnouncementSupplySourceSnapshot(OTHER_COMPLEX, "059.9100A",
                "59.9100", "", "500", "9", "30000000", "200000"));
        refinement.refineAll();
        long id = announcements.findAllByLhPanIdOrderByIdAsc(PAN_ID).getFirst().getId();

        var workspace = supplyCorrections.workspace(id);

        var keys = workspace.lhSupplyRows().stream().map(SupplyCorrection.LhSupplyRow::attachedRowKey).toList();
        assertThat(keys).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(workspace.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::ambiguous).containsOnly(false);
        assertThat(workspace.rows()).extracting(Row::complexName).containsExactlyInAnyOrder(COMPLEX_NAME, OTHER_COMPLEX);
    }

    @Test
    void PDF_추출_입력은_작업실과_같은_LH_공급_원천_행을_같은_행_식별자로_담는다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"), supply("059.9300B", "59.9300", "15"));
        // 공고목록 조건과 다른 요청으로 나중에 받은 공급 원천은 정제와 작업실이 쓰지 않으므로 추출 입력에도 담지 않는다.
        lhStorage.replaceSupplies(PAN_ID, new LhAnnouncementRequest(PAN_ID, "03", "06", "10", "064").requestDescription(),
                List.of(LhSourceViews.supply(0, PAN_ID, supply("다른 요청", "84.0000", "9"))));

        var input = extractionInputs.read(id);

        String firstRow = LhSourceIdentifier.supply(PAN_ID, 0);
        String second = LhSourceIdentifier.supply(PAN_ID, 1);
        assertThat(input.sourceSupplyRows()).extracting(PdfExtractionInput.SourceSupplyRow::source,
                PdfExtractionInput.SourceSupplyRow::rowIdentifier, PdfExtractionInput.SourceSupplyRow::typeName,
                PdfExtractionInput.SourceSupplyRow::suppliedUnitCount)
                .containsExactly(tuple("LH", firstRow, "공공임대", "240"), tuple("LH", second, "059.9300B", "15"));
        assertThat(supplyCorrections.workspace(id).lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::rowIdentifier)
                .containsExactly(firstRow, second);
        assertThat(extractionInputs.read(-1L).sourceSupplyRows()).isEmpty();
    }

    @Test
    void 같은_작업_번호_재전송은_다시_저장하지_않고_PDF_보정한_공고는_LH_재정제가_공급행을_바꾸지_않는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = supplyCorrections.workspace(id);
        var row = workspace.rows().getFirst();
        var request = request(workspace, List.of(edit(row, row.id(), row.key(), "59A", type5991, 24)));
        var preview = supplyCorrections.preview(id, request);
        var apply = new SupplyCorrection.Apply(request, preview.confirmationToken());
        var first = supplyCorrections.apply(id, apply, "operator");
        var saved = rowValues(id);

        var again = supplyCorrections.apply(id, apply, "operator");

        assertThat(again.alreadyApplied()).isTrue();
        assertThat(again.changeId()).isEqualTo(first.changeId());
        assertThat(rowValues(id)).isEqualTo(saved);
        supplies(supply("059.9100A", "59.9100", "26"), supply("059.9300B", "59.9300", "15"));
        refinement.refineAll();
        assertThat(rowValues(id)).isEqualTo(saved);
        assertThat(supplyOwner(id)).isEqualTo("ANNOUNCEMENT_DOCUMENT");
    }

    /** 주택형을 정하지 않고 PDF 형명·전용면적만 준 새 행 */
    private Row areaRow(Row base, String key, String typeName, BigDecimal area, int count, int order) {
        return new Row(null, key, complex.getId(), base.housingComplexName(), null, null, null, null, false, false, order,
                base.complexName(), typeName, null, base.moveInMonth(), base.category(), count, false, area, null, List.of(), false,
                List.of());
    }

    private static Row excluded(Row row) {
        return new Row(row.id(), row.key(), row.housingComplexId(), row.housingComplexName(), row.housingTypeId(), null,
                row.groupId(), row.sharedHouseholdCount(), row.protectedValues(), true, row.displayOrder(), row.complexName(),
                row.typeName(), row.pnu(), row.moveInMonth(), row.category(), row.householdCount(), row.areaOverride(),
                row.exclusiveArea(), row.supplyArea(), row.targets(), row.targetCountsComparable(), null);
    }

    /** 기존 행 base의 단지·공급구분을 쓰는 요청 행. 기존 행은 공급대상을 그대로 두고 새 행은 공급대상 없이 만든다. */
    private Row edit(Row base, Long id, String key, String typeName, HousingType type, Integer count) {
        Long typeId = base.housingTypeId();
        if (type != null) { typeId = type.getId(); }
        int order = base.displayOrder();
        List<SupplyCorrection.Target> targets = base.targets();
        if (id == null) {
            order = 1;
            if (key.endsWith("15")) { order = 2; }
            targets = List.of();
        }
        return new Row(id, key, complex.getId(), base.housingComplexName(), typeId, null, null, null, false, false, order,
                base.complexName(), typeName, null, base.moveInMonth(), base.category(), count, false, null, null, targets,
                false, List.of());
    }

    private SupplyCorrection.Request request(SupplyCorrection.Workspace workspace, List<Row> rows) {
        return new SupplyCorrection.Request(UUID.randomUUID(), workspace.snapshotToken(), "공고문 공급 표 대조",
                "https://apply.lh.or.kr/notice", "3", rows);
    }

    private AnnouncementJsonCorrection.Request jsonRequest(AnnouncementJsonCorrection.Workspace workspace, List<Row> rows)
            throws Exception {
        var document = json.readValue(new ClassPathResource("announcement/import-v1-valid.json").getInputStream(),
                AdminAnnouncementImportRequest.class);
        return new AnnouncementJsonCorrection.Request(workspace.snapshotToken(), document, List.of(), List.of(), List.of(),
                request(workspace.supply(), rows), List.of(), false);
    }

    private void applySupply(long id, SupplyCorrection.Request request) {
        var preview = supplyCorrections.preview(id, request);
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
    }

    private void rejects(long id, SupplyCorrection.Workspace workspace, List<Row> rows, String message) {
        assertThatThrownBy(() -> supplyCorrections.preview(id, request(workspace, rows)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private long refinedAnnouncement(LhAnnouncementSupplySourceSnapshot... rows) {
        publish(PAN_ID);
        details(PAN_ID);
        supplies(rows);
        refinement.refineAll();
        return announcements.findAllByLhPanIdOrderByIdAsc(PAN_ID).getFirst().getId();
    }

    private List<List<Object>> rowValues(long id) {
        return jdbc.sql("""
                SELECT id, source_housing_type_name, coalesce(announcement_housing_type_name, ''), coalesce(housing_type_id, 0),
                    coalesce(total_supply_household_count, -1), admin_modified, admin_excluded::text
                FROM supply_rows WHERE announcement_id = :id ORDER BY id""").param("id", id)
                .query((rs, index) -> List.<Object>of(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getInt(5), rs.getBoolean(6), rs.getString(7))).list();
    }




    private String supplyOwner(long id) {
        return jdbc.sql("SELECT supply_owner FROM announcements WHERE id = :id").param("id", id).query(String.class).single();
    }

    private void publish(String panId) {
        LocalDate noticeDate = LocalDate.now().minusDays(1);
        catalogEntries.removeIf(entry -> entry.snapshot().panId().equals(panId));
        catalogEntries.add(new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063",
                "대구시 달서구·달성군 10년 공공임대리츠 예비입주자 모집", "공고중", noticeDate.format(CATALOG_DATE),
                noticeDate.format(CATALOG_DATE), LocalDate.now().plusDays(10).format(CATALOG_DATE),
                "https://apply.lh.or.kr/" + panId, ""), "{}"));
        catalog.store(List.copyOf(catalogEntries));
    }

    private void details(String panId) {
        String start = LocalDate.now().plusDays(1).format(COMPACT_DATE);
        String end = LocalDate.now().plusDays(3).format(COMPACT_DATE);
        String winner = LocalDate.now().plusDays(20).format(COMPACT_DATE);
        List<LhAnnouncementDetailSourceView> views = new ArrayList<>(List.of(
                LhSourceViews.detail(0, panId, new LhAnnouncementDetailSourceSnapshot("COMPLEX", COMPLEX_NAME, ADDRESS,
                        null, "1000", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null)),
                LhSourceViews.detail(1, panId, new LhAnnouncementDetailSourceSnapshot("SCHEDULE", COMPLEX_NAME, null,
                        null, null, null, null, null, null, null, start, end, winner, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null))));
        if (otherComplex) {
            views.add(LhSourceViews.detail(2, panId, new LhAnnouncementDetailSourceSnapshot("COMPLEX", OTHER_COMPLEX, OTHER_ADDRESS,
                    null, "500", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null)));
        }
        lhStorage.replaceDetails(panId, request(panId).requestDescription(), views);
    }

    private void supplies(LhAnnouncementSupplySourceSnapshot... rows) {
        supplies(PAN_ID, rows);
    }

    private void supplies(String panId, LhAnnouncementSupplySourceSnapshot... rows) {
        List<LhAnnouncementSupplySourceView> views = new ArrayList<>();
        for (int index = 0; index < rows.length; index++) {
            views.add(LhSourceViews.supply(index, panId, rows[index]));
        }
        lhStorage.replaceSupplies(panId, request(panId).requestDescription(), views);
    }

    private LhAnnouncementSupplySourceSnapshot supply(String typeName, String area, String count) {
        return new LhAnnouncementSupplySourceSnapshot(COMPLEX_NAME, typeName, area, "", "1000", count, "30000000", "200000");
    }

    private LhAnnouncementRequest request(String panId) {
        return new LhAnnouncementRequest(panId, "03", "06", "10", "063");
    }
}
