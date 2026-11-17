package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.fixture.SupplySheetInputs;
import com.toadzip.backend.announcement.domain.SupplyQuota;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * LH 공고 정제로 만든 공고의 공급표 보정. LH 공급 원천 행은 저장하지 않고 작업실의 검산용 참고(sourceReference)로만 보이며, PDF 보정은
 * 원천 행 수와 상관없이 공급표의 주택형·칸·모집수를 고친다.
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
    void LH가_신청자_묶음마다_나눈_세_줄은_주택형_하나의_공급대상_칸이_되고_PDF_보정은_칸_모집수만_고친다() throws Exception {
        var type36 = types.save(HousingType.createFromMyHome(complex, "complex-747:36", "36A", new BigDecimal("36.8500"), null));
        long id = refinedAnnouncement(supply("36A형(고령자)", "36.8500", "62"), supply("36A형(신혼부부·한부모가족)", "36.85", "388"),
                supply("36A형(청년)", "36.8500", "116"));
        var workspace = jsonCorrections.workspace(id);
        var sheet = workspace.supply().sheet();
        assertThat(sheet.housingTypes()).singleElement()
                .satisfies(type -> assertThat(type.housingTypeId()).isEqualTo(type36.getId()));
        assertThat(winners(sheet)).containsExactlyInAnyOrderEntriesOf(Map.of("고령자", 62, "신혼부부·한부모가족", 388, "청년", 116));
        // 원천 행은 저장하지 않고 검산용으로만 보인다.
        assertThat(workspace.supply().sourceReference()).extracting(SupplyCorrection.SourceReference::typeName,
                SupplyCorrection.SourceReference::householdCount)
                .containsExactly(tuple("36A형(고령자)", 62), tuple("36A형(신혼부부·한부모가족)", 388), tuple("36A형(청년)", 116));
        // 공고문이 고령자 모집수를 60으로 적었다. 원천 합(566)과 달라도 칸 모집수로 저장한다.
        var input = SupplySheetInputs.withQuota(SupplySheetInputs.of(sheet), SupplyQuota.Kind.WINNER, 60, cellKey(sheet, "고령자"));
        var request = jsonRequest(workspace, input);

        var preview = jsonCorrections.preview(id, request);
        jsonCorrections.apply(id, new AnnouncementJsonCorrection.Apply(request, preview.confirmationToken()), "operator");

        var after = supplyCorrections.workspace(id).sheet();
        assertThat(winners(after)).containsExactlyInAnyOrderEntriesOf(Map.of("고령자", 60, "신혼부부·한부모가족", 388, "청년", 116));
        // 주택형·공급대상·칸은 같은 개체로 남는다.
        assertThat(after.housingTypes()).extracting(SupplySheetView.HousingType::key)
                .containsExactlyElementsOf(sheet.housingTypes().stream().map(SupplySheetView.HousingType::key).toList());
        assertThat(after.targets()).extracting(SupplySheetView.Target::key)
                .containsExactlyInAnyOrderElementsOf(sheet.targets().stream().map(SupplySheetView.Target::key).toList());
        assertThat(after.cells()).extracting(SupplySheetView.Cell::key)
                .containsExactlyInAnyOrderElementsOf(sheet.cells().stream().map(SupplySheetView.Cell::key).toList());
    }

    @Test
    void 같은_주택타입을_가리키는_신청형이_다른_LH_행은_신청형마다_주택형이고_같은_신청형의_새_주택형은_거절한다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"), supply("059.9100C(주거약자)", "59.9100", "4"));
        var workspace = supplyCorrections.workspace(id);
        var sheet = workspace.sheet();
        assertThat(sheet.housingTypes()).extracting(SupplySheetView.HousingType::name, SupplySheetView.HousingType::housingTypeId)
                .containsExactlyInAnyOrder(tuple("059.9100A", type5991.getId()), tuple("059.9100C(주거약자)", type5991.getId()));
        assertThat(workspace.applicationTypeNames()).containsExactlyInAnyOrder("059.9100A", "059.9100C(주거약자)");
        var weak = housingType(sheet, "059.9100C(주거약자)");

        var duplicated = SupplySheetInputs.plus(SupplySheetInputs.of(sheet), SupplySheetInputs.newHousingType("new:dup",
                weak.announcementComplexId(), weak.complexName(), complex.getId(), weak.name(), null, 5));
        assertThatThrownBy(() -> supplyCorrections.preview(id, request(workspace, duplicated)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("한 단지에서 주택형(신청형)은 하나입니다");

        String weakCell = sheet.cells().stream().filter(cell -> cell.housingType().equals(weak.key())).findFirst().orElseThrow().key();
        applySupply(id, request(workspace, SupplySheetInputs.withQuota(SupplySheetInputs.of(sheet), SupplyQuota.Kind.WINNER, 5,
                weakCell)));
        var after = supplyCorrections.workspace(id).sheet();
        assertThat(after.quotas()).filteredOn(quota -> quota.members().equals(List.of(weakCell)))
                .extracting(SupplySheetView.Quota::kind, SupplySheetView.Quota::count, SupplySheetView.Quota::adminModified)
                .contains(tuple(SupplyQuota.Kind.WINNER, 5, true));
    }

    @Test
    void 주택형을_정하지_않고_전용면적만_준_새_주택형은_서버가_같은_면적_주택형을_고르거나_새로_만든다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = supplyCorrections.workspace(id);
        var base = workspace.sheet().housingTypes().getFirst();
        // 둘째 자리까지 같으면 같은 주택형이다(59.931 → 59.93).
        var sameArea = SupplySheetInputs.newHousingType("new:59b", base.announcementComplexId(), base.complexName(),
                complex.getId(), "59B", new BigDecimal("59.931"), 1);
        // 공고 표기를 이어 적은 주택형이라도 단지 주택형 이름은 첫 표기에서 신청자 구분을 뗀 이름이다.
        var newArea = SupplySheetInputs.newHousingType("new:84", base.announcementComplexId(), base.complexName(),
                complex.getId(), "84A(고령자)·84B", new BigDecimal("84.96"), 2);
        // 84A와 84A-1은 전용면적이 같아도 이름이 달라 다른 주택타입이다.
        var otherName = SupplySheetInputs.newHousingType("new:84-1", base.announcementComplexId(), base.complexName(),
                complex.getId(), "84A-1", new BigDecimal("84.964"), 3);
        var request = request(workspace, SupplySheetInputs.plus(SupplySheetInputs.of(workspace.sheet()), sameArea, newArea,
                otherName));

        var preview = supplyCorrections.preview(id, request);

        assertThat(housingType(preview.after(), "59B").housingTypeId()).isEqualTo(type5993.getId());
        assertThat(housingType(preview.after(), "84A(고령자)·84B")).satisfies(type -> {
            assertThat(type.housingTypeId()).isNull();
            assertThat(type.newHousingType().name()).isEqualTo("84A");
        });
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
        assertThat(jdbc.sql("SELECT name FROM housing_types WHERE housing_complex_id = :id AND exclusive_area BETWEEN 84.955 AND 84.965")
                .param("id", complex.getId()).query(String.class).list()).containsExactlyInAnyOrder("84A", "84A-1");
        var saved = supplyCorrections.workspace(id).sheet();
        assertThat(saved.housingTypes()).extracting(SupplySheetView.HousingType::housingTypeId).doesNotContainNull()
                .doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    void 주택형이_없던_LH_주택형에_PDF_면적을_주면_새_주택형을_만들어_연결하고_연결을_끊은_요청은_다시_채우지_않는다() {
        long id = refinedAnnouncement(supply("084.9600A", "84.9600", "7"));
        var workspace = supplyCorrections.workspace(id);
        var type = workspace.sheet().housingTypes().getFirst();
        assertThat(type.housingTypeId()).isNull();
        var withArea = SupplySheetInputs.withHousingType(SupplySheetInputs.of(workspace.sheet()), type.key(),
                input -> SupplySheetInputs.renamed(SupplySheetInputs.linked(input, complex.getId(), null, null,
                        new BigDecimal("84.96")), "84A"));

        applySupply(id, request(workspace, withArea));

        var linked = supplyCorrections.workspace(id);
        Long created = linked.sheet().housingTypes().getFirst().housingTypeId();
        assertThat(created).isNotNull();
        assertThat(types.findById(created).orElseThrow().getName()).isEqualTo("84A");
        var cleared = SupplySheetInputs.withHousingType(SupplySheetInputs.of(linked.sheet()), type.key(),
                input -> SupplySheetInputs.linked(input, input.housingComplexId(), null, null, input.exclusiveArea()));
        applySupply(id, request(linked, cleared));
        assertThat(supplyCorrections.workspace(id).sheet().housingTypes().getFirst().housingTypeId()).isNull();
    }

    @Test
    void PDF_추출_입력은_작업실과_같은_LH_공급_원천_행을_담는다() {
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
        assertThat(supplyCorrections.workspace(id).sourceReference()).extracting(SupplyCorrection.SourceReference::typeName,
                SupplyCorrection.SourceReference::exclusiveArea, SupplyCorrection.SourceReference::householdCount)
                .containsExactly(tuple("공공임대", null, 240), tuple("059.9300B", new BigDecimal("59.93"), 15));
        assertThat(extractionInputs.read(-1L).sourceSupplyRows()).isEmpty();
    }

    @Test
    void 같은_작업_번호_재전송은_다시_저장하지_않고_PDF_보정한_공고는_LH_재정제가_공급표를_바꾸지_않는다() {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = supplyCorrections.workspace(id);
        var sheet = workspace.sheet();
        var type = sheet.housingTypes().getFirst();
        var input = SupplySheetInputs.withHousingType(SupplySheetInputs.of(sheet), type.key(),
                stored -> SupplySheetInputs.renamed(stored, "59A"));
        var request = request(workspace, SupplySheetInputs.withQuota(input, SupplyQuota.Kind.WINNER, 24,
                sheet.cells().getFirst().key()));
        var preview = supplyCorrections.preview(id, request);
        var apply = new SupplyCorrection.Apply(request, preview.confirmationToken());
        var first = supplyCorrections.apply(id, apply, "operator");
        var saved = supplyCorrections.workspace(id).sheet();

        var again = supplyCorrections.apply(id, apply, "operator");

        assertThat(again.alreadyApplied()).isTrue();
        assertThat(again.changeId()).isEqualTo(first.changeId());
        assertThat(supplyCorrections.workspace(id).sheet()).isEqualTo(saved);
        assertThat(saved.housingTypes()).extracting(SupplySheetView.HousingType::name).containsExactly("59A");
        supplies(supply("059.9100A", "59.9100", "26"), supply("059.9300B", "59.9300", "15"));
        refinement.refineAll();
        assertThat(supplyCorrections.workspace(id).sheet()).isEqualTo(saved);
        assertThat(supplyConfirmed(id)).isTrue();
    }

    @Test
    void PDF로_확정한_LH_공고는_기본_정보를_고치지_않았어도_재정제가_공고명과_일정을_덮지_않고_원천_확인을_표시한다()
            throws Exception {
        long id = confirmedAnnouncement();
        var confirmed = announcements.findById(id).orElseThrow();
        var saved = supplyCorrections.workspace(id).sheet();
        var schedules = schedules(id);

        publish(PAN_ID, "[정정] 대구시 달서구·달성군 10년 공공임대리츠 예비입주자 모집");
        details(PAN_ID, 30, null);
        supplies(supply("059.9100A", "59.9100", "26"));
        refinement.refineAll();

        var after = announcements.findById(id).orElseThrow();
        assertThat(after.getName()).isEqualTo(confirmed.getName());
        assertThat(after.getWinnerAnnouncementDate()).isEqualTo(confirmed.getWinnerAnnouncementDate());
        assertThat(schedules(id)).isEqualTo(schedules);
        assertThat(after.isSourceReviewRequired()).isTrue();
        assertThat(supplyCorrections.workspace(id).sheet()).isEqualTo(saved);
        assertThat(after.isSupplyRecheckRequired()).isTrue();
    }

    @Test
    void PDF로_확정한_LH_공고는_LH가_새_일정을_더해도_일정_행을_만들지_않고_원천_확인을_표시한다() throws Exception {
        long id = confirmedAnnouncement();
        var schedules = schedules(id);

        details(PAN_ID, 20, LocalDate.now().plusDays(10).format(COMPACT_DATE));
        refinement.refineAll();

        assertThat(schedules(id)).isEqualTo(schedules);
        assertThat(announcements.findById(id).orElseThrow().isSourceReviewRequired()).isTrue();
    }

    @Test
    void PDF로_확정한_LH_공고를_같은_원천으로_다시_정제하면_일정_행을_그대로_두고_원천_확인을_켜지_않는다() throws Exception {
        long id = confirmedAnnouncement();
        var schedules = schedules(id);

        refinement.refineAll();

        assertThat(schedules(id)).isEqualTo(schedules);
        assertThat(announcements.findById(id).orElseThrow().isSourceReviewRequired()).isFalse();
    }

    /** 기본 정보는 고치지 않고 공고문 보정만 적용해 정본을 확정한 LH 공고다. */
    private long confirmedAnnouncement() throws Exception {
        long id = refinedAnnouncement(supply("059.9100A", "59.9100", "25"));
        var workspace = jsonCorrections.workspace(id);
        var request = jsonRequest(workspace, SupplySheetInputs.of(workspace.supply().sheet()));
        jsonCorrections.apply(id, new AnnouncementJsonCorrection.Apply(request,
                jsonCorrections.preview(id, request).confirmationToken()), "operator");
        var confirmed = announcements.findById(id).orElseThrow();
        assertThat(confirmed.isSupplyConfirmed()).isTrue();
        assertThat(confirmed.isAdminModified()).isFalse();
        assertThat(confirmed.isSourceReviewRequired()).isFalse();
        return id;
    }

    private List<String> schedules(long announcementId) {
        return jdbc.sql("""
                SELECT schedule_type || ' ' || name || ' ' || start_at || ' ' || end_at FROM announcement_schedules
                WHERE announcement_id = ? ORDER BY id
                """).param(announcementId).query(String.class).list();
    }

    private SupplyCorrection.Request request(SupplyCorrection.Workspace workspace, SupplySheetInput sheet) {
        return new SupplyCorrection.Request(UUID.randomUUID(), workspace.snapshotToken(), "공고문 공급 표 대조",
                "https://apply.lh.or.kr/notice", "3", sheet);
    }

    private AnnouncementJsonCorrection.Request jsonRequest(AnnouncementJsonCorrection.Workspace workspace, SupplySheetInput sheet)
            throws Exception {
        var document = json.readValue(new ClassPathResource("announcement/import-v1-valid.json").getInputStream(),
                AdminAnnouncementImportRequest.class);
        return new AnnouncementJsonCorrection.Request(workspace.snapshotToken(), document, List.of(), List.of(), List.of(),
                request(workspace.supply(), sheet), List.of(), false);
    }

    private void applySupply(long id, SupplyCorrection.Request request) {
        var preview = supplyCorrections.preview(id, request);
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
    }

    private static SupplySheetView.HousingType housingType(SupplySheetView sheet, String name) {
        return sheet.housingTypes().stream().filter(type -> type.name().equals(name)).findFirst().orElseThrow();
    }

    /** 공급대상 표기가 label인 칸 key. 주택형이 하나인 공급표에서 쓴다. */
    private static String cellKey(SupplySheetView sheet, String label) {
        String target = sheet.targets().stream().filter(candidate -> candidate.label().equals(label)).findFirst().orElseThrow().key();
        return sheet.cells().stream().filter(cell -> cell.target().equals(target)).findFirst().orElseThrow().key();
    }

    /** 칸 하나만 가진 당첨 수를 공급대상 표기별로. */
    private static Map<String, Integer> winners(SupplySheetView sheet) {
        Map<String, String> labels = sheet.targets().stream()
                .collect(Collectors.toMap(SupplySheetView.Target::key, SupplySheetView.Target::label));
        Map<String, String> cellLabels = sheet.cells().stream()
                .collect(Collectors.toMap(SupplySheetView.Cell::key, cell -> labels.get(cell.target())));
        return sheet.quotas().stream().filter(quota -> quota.kind() == SupplyQuota.Kind.WINNER && quota.members().size() == 1)
                .collect(Collectors.toMap(quota -> cellLabels.get(quota.members().getFirst()), SupplySheetView.Quota::count));
    }

    private long refinedAnnouncement(LhAnnouncementSupplySourceSnapshot... rows) {
        publish(PAN_ID);
        details(PAN_ID);
        supplies(rows);
        refinement.refineAll();
        long id = announcements.findAllByLhPanIdOrderByIdAsc(PAN_ID).getFirst().getId();
        // 정제는 준비 줄만 쓴다. 보정은 준비 데이터로 시작한 공고 공급표(정본)를 고친다.
        var workspace = supplyCorrections.workspace(id);
        supplyCorrections.startFromStaging(id,
                new SupplyCorrection.StartFromStaging(workspace.snapshotToken(), workspace.stagingToken()), "operator");
        return id;
    }

    private boolean supplyConfirmed(long id) {
        return jdbc.sql("SELECT supply_confirmed_at IS NOT NULL FROM announcements WHERE id = :id").param("id", id)
                .query(Boolean.class).single();
    }

    private void publish(String panId) {
        publish(panId, "대구시 달서구·달성군 10년 공공임대리츠 예비입주자 모집");
    }

    private void publish(String panId, String title) {
        LocalDate noticeDate = LocalDate.now().minusDays(1);
        catalogEntries.removeIf(entry -> entry.snapshot().panId().equals(panId));
        catalogEntries.add(new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063",
                title, "공고중", noticeDate.format(CATALOG_DATE),
                noticeDate.format(CATALOG_DATE), LocalDate.now().plusDays(10).format(CATALOG_DATE),
                "https://apply.lh.or.kr/" + panId, ""), "{}"));
        catalog.store(List.copyOf(catalogEntries));
    }

    private void details(String panId) {
        details(panId, 20, null);
    }

    /** documentTarget은 서류제출 대상자 발표일(yyyyMMdd)이고, 없으면 null이다. */
    private void details(String panId, int winnerAfterDays, String documentTarget) {
        String start = LocalDate.now().plusDays(1).format(COMPACT_DATE);
        String end = LocalDate.now().plusDays(3).format(COMPACT_DATE);
        String winner = LocalDate.now().plusDays(winnerAfterDays).format(COMPACT_DATE);
        List<LhAnnouncementDetailSourceView> views = new ArrayList<>(List.of(
                LhSourceViews.detail(0, panId, new LhAnnouncementDetailSourceSnapshot("COMPLEX", COMPLEX_NAME, ADDRESS,
                        null, "1000", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null)),
                LhSourceViews.detail(1, panId, new LhAnnouncementDetailSourceSnapshot("SCHEDULE", COMPLEX_NAME, null,
                        null, null, null, null, null, null, null, start, end, winner, documentTarget, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null))));
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
