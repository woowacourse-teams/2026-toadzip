package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.LinkedHousingType;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.SaveMode;
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
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.enrichment.service.LhScheduleAttachmentWriter;
import com.toadzip.backend.ingest.lhrefinement.service.LhAnnouncementRefinementService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** LH가 한 행으로 내린 공급정보를 관리자가 공고문 PDF 근거로 주택형별 행으로 나눠 저장하는 보정. LH 공고 정제로 만든 LH 전용 공고에서 확인한다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@ExtendWith(IngestExecutionExtension.class)
@WithMockUser(username = "operator", roles = "ADMIN")
class LhSupplySplitCorrectionIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String COMPLEX_NAME = "대구테크노폴리스4";
    private static final String ADDRESS = "대구광역시 달성군 현풍읍 테크노대로 100";
    private static final String PAN_ID = "P747";
    private static final String MERGED = LhSourceIdentifier.supply(PAN_ID, 0);

    @Autowired private LhAnnouncementRefinementService refinement;
    @Autowired private SupplyCorrectionService supplyCorrections;
    @Autowired private AnnouncementJsonCorrectionService jsonCorrections;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private LhStorageFixtures lhStorage;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
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
    void PDF_보정으로_LH_통합행_240을_주택형별_200_25_15로_나누면_통합행이_첫_분리행이_되고_합계는_한_번만_센다() throws Exception {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = jsonCorrections.workspace(id);
        var merged = workspace.supply().rows().getFirst();
        assertThat(workspace.supply().lhSupplyRows()).singleElement().satisfies(lh -> {
            assertThat(lh.rowIdentifier()).isEqualTo(MERGED);
            assertThat(lh.attachedRowKey()).isEqualTo(merged.key());
            assertThat(lh.householdCount()).isEqualTo(240);
        });
        var request = jsonRequest(workspace, splitRows(merged));

        var preview = jsonCorrections.preview(id, request);

        assertThat(preview.supply().splits()).singleElement().satisfies(split -> {
            assertThat(split.lhSourceRowIdentifier()).isEqualTo(MERGED);
            assertThat(split.rowKeys()).containsExactly(merged.key(), "split-25", "split-15");
            assertThat(split.householdCount()).isEqualTo(240);
            assertThat(split.lhHouseholdCount()).isEqualTo(240);
        });
        assertThat(rowCount(id)).isOne();

        jsonCorrections.apply(id, new AnnouncementJsonCorrection.Apply(request, preview.confirmationToken()), "operator");

        var reloaded = supplyCorrections.workspace(id);
        assertThat(reloaded.rows()).extracting(Row::id, Row::typeName, Row::housingTypeId, Row::householdCount)
                .containsExactly(tuple(merged.id(), "051.9800A", type5198.getId(), 200),
                        tuple(reloaded.rows().get(1).id(), "059.9100A", type5991.getId(), 25),
                        tuple(reloaded.rows().get(2).id(), "059.9300B", type5993.getId(), 15));
        assertThat(householdSum(id)).isEqualTo(240);
        // 통합행은 ID와 LH 행 대응을 유지하고, 세 행 모두 같은 LH 행에서 나눴다고 기록한다.
        assertThat(jdbc.sql("""
                SELECT id = :merged, lh_source_supply_row_identifier, lh_split_source_identifier FROM supply_rows
                WHERE announcement_id = :id ORDER BY display_order""").param("merged", merged.id()).param("id", id)
                .query((rs, index) -> tuple(rs.getBoolean(1), rs.getString(2), rs.getString(3))).list())
                .containsExactly(tuple(true, MERGED, MERGED), tuple(false, null, MERGED), tuple(false, null, MERGED));
        assertThat(reloaded.rowSources()).filteredOn(source -> source.category().equals("LH_SPLIT"))
                .extracting(SupplyCorrection.RowSource::rowKey, SupplyCorrection.RowSource::sourceKey)
                .containsExactlyElementsOf(reloaded.rows().stream().map(row -> tuple(row.key(), MERGED)).toList());
        assertThat(reloaded.lhSupplyRows()).singleElement().satisfies(lh ->
                assertThat(lh.splitRowKeys()).containsExactlyInAnyOrderElementsOf(reloaded.rows().stream().map(Row::key).toList()));
        assertThat(reloaded.unattachedLhSupplyRows()).isEmpty();
        assertThat(supplyOwner(id)).isEqualTo("ANNOUNCEMENT_DOCUMENT");
    }

    @Test
    void PDF_보정에서_저장_방식만_적은_기존_행은_공급정보를_바꾼_요청으로_보지_않는다() throws Exception {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = jsonCorrections.workspace(id);
        var merged = workspace.supply().rows().getFirst();
        var unchanged = new Row(merged.id(), merged.key(), merged.housingComplexId(), merged.housingComplexName(),
                merged.housingTypeId(), null, merged.groupId(), merged.sharedHouseholdCount(), merged.protectedValues(),
                merged.excluded(), merged.displayOrder(), merged.complexName(), merged.typeName(), merged.pnu(),
                merged.moveInMonth(), merged.category(), merged.householdCount(), merged.areaOverride(), merged.exclusiveArea(),
                merged.supplyArea(), merged.targets(), merged.targetCountsComparable(), merged.linkedHousingTypes(),
                SaveMode.UPDATE, null);

        assertThatThrownBy(() -> jsonCorrections.preview(id, jsonRequest(workspace, List.of(unchanged))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("적용할 변경이 없습니다");
    }

    @Test
    void 나눈_공급행_하나를_지우면_미리보기가_분리_합계_변화를_경고한다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = supplyCorrections.workspace(id);
        applySupply(id, request(workspace, splitRows(workspace.rows().getFirst())));
        var after = supplyCorrections.workspace(id);
        var removed = after.rows().get(2);
        assertThat(after.deletableRowKeys()).contains(removed.key());

        var preview = supplyCorrections.preview(id, new SupplyCorrection.Request(UUID.randomUUID(), after.snapshotToken(),
                "중복 행 정리", "https://apply.lh.or.kr/notice", null, after.rows().subList(0, 2), List.of(),
                List.of(removed.key())));

        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("분리 합계가 240에서 225(으)로 바뀝니다"));
        assertThat(preview.splits()).singleElement().satisfies(split -> assertThat(split.householdCount()).isEqualTo(225));
    }

    @Test
    void 아직_공급행이_되지_않은_LH_행이_있어도_붙이기와_분리로_저장하고_같은_LH_행의_공급행을_중복으로_만들지_않는다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        supplies(supply("공공임대", "", "240"), supply("059.9300B", "59.9300", "15"));
        var workspace = supplyCorrections.workspace(id);
        String unattached = LhSourceIdentifier.supply(PAN_ID, 1);
        assertThat(workspace.unattachedLhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::rowIdentifier)
                .containsExactly(unattached);
        var merged = workspace.rows().getFirst();
        var rows = List.of(
                edit(merged, merged.id(), merged.key(), "051.9800A", type5198, 200, SaveMode.SPLIT, MERGED),
                edit(merged, null, "split-25", "059.9100A", type5991, 25, SaveMode.SPLIT, MERGED),
                edit(merged, null, "attach-15", "059.9300B", type5993, 15, SaveMode.ATTACH, unattached));

        var request = request(workspace, rows);

        var preview = supplyCorrections.preview(id, request);

        assertThat(preview.unattachedLhSupplyRows()).isEmpty();
        assertThat(preview.warnings()).noneMatch(warning -> warning.contains("아직 공급행이 되지 않았습니다"));
        supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()), "operator");
        assertThat(jdbc.sql("""
                SELECT lh_source_supply_row_identifier, lh_split_source_identifier, total_supply_household_count FROM supply_rows
                WHERE announcement_id = :id ORDER BY display_order""").param("id", id)
                .query((rs, index) -> tuple(rs.getString(1), rs.getString(2), rs.getInt(3))).list())
                .containsExactly(tuple(MERGED, MERGED, 200), tuple(null, MERGED, 25), tuple(unattached, null, 15));
        var reloaded = supplyCorrections.workspace(id);
        assertThat(reloaded.lhSupplyRows()).extracting(SupplyCorrection.LhSupplyRow::attachedRowKey)
                .containsExactly(merged.key(), reloaded.rows().get(2).key());
    }

    @Test
    void 분리와_붙이기가_같은_LH_행에_공급행을_중복으로_만들거나_통합행을_남기면_거절한다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        supplies(supply("공공임대", "", "240"), supply("059.9300B", "59.9300", "15"));
        var workspace = supplyCorrections.workspace(id);
        var merged = workspace.rows().getFirst();
        String unattached = LhSourceIdentifier.supply(PAN_ID, 1);
        var kept = edit(merged, merged.id(), merged.key(), merged.typeName(), null, 240, null, null);

        rejects(id, workspace, List.of(kept, edit(merged, null, "dup", "059.9100A", type5991, 25, SaveMode.ATTACH, MERGED)),
                "이미 공급행이 된 LH 공급 원본입니다");
        rejects(id, workspace, List.of(kept, edit(merged, null, "a", "059.9100A", type5991, 25, SaveMode.ATTACH, unattached),
                edit(merged, null, "b", "059.9300B", type5993, 15, SaveMode.ATTACH, unattached)), "둘 이상 붙일 수 없습니다");
        rejects(id, workspace, List.of(kept, edit(merged, null, "c", "059.9100A", type5991, 25, SaveMode.SPLIT, MERGED),
                edit(merged, null, "d", "059.9300B", type5993, 15, SaveMode.SPLIT, MERGED)), "통합행");
        rejects(id, workspace, List.of(edit(merged, merged.id(), merged.key(), "051.9800A", type5198, 240, SaveMode.SPLIT, MERGED)),
                "둘 이상으로 저장하세요");
        rejects(id, workspace, List.of(kept, edit(merged, null, "e", "059.9100A", type5991, 25, SaveMode.SPLIT,
                LhSourceIdentifier.supply("OTHER", 0))), "최신 LH 공급 원본에 없는 행");
        rejects(id, workspace, List.of(kept, edit(merged, null, "f", "059.9100A", type5991, 25, SaveMode.ADD, MERGED)),
                "분리(SPLIT)나 붙이기(ATTACH)에서만");
        assertThat(rowCount(id)).isOne();
    }

    @Test
    void LH_공고의_공통_모집수는_공급행_하나에_여러_주택형을_연결해_한_번만_저장하고_마이홈_묶음은_이유를_알려_거절한다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = supplyCorrections.workspace(id);
        var merged = workspace.rows().getFirst();
        var grouped = new SupplyCorrection.Request(UUID.randomUUID(), workspace.snapshotToken(), "공고문 확인",
                "https://apply.lh.or.kr/notice", null, List.of(edit(merged, merged.id(), merged.key(), "051.9800A",
                type5198, null, null, null), edit(merged, null, "member", "059.9100A", type5991, null, null, null)),
                List.of(new SupplyCorrection.NewGroup(merged.id(), List.of(merged.key(), "member"), 240)), List.of());
        assertThatThrownBy(() -> supplyCorrections.preview(id, grouped)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("마이홈 공고 원천이 없는 공고는 공통 모집 묶음을 만들 수 없습니다");

        var shared = edit(merged, merged.id(), merged.key(), "051.9800A·059.9100A·059.9300B", type5198, 240, null, null)
                .withLinkedHousingTypes(List.of(new LinkedHousingType(type5991.getId(), null),
                        new LinkedHousingType(type5993.getId(), null)));
        applySupply(id, request(workspace, List.of(shared)));

        assertThat(rowCount(id)).isOne();
        assertThat(householdSum(id)).isEqualTo(240);
        assertThat(jdbc.sql("SELECT housing_type_id FROM supply_row_housing_types WHERE supply_row_id = :row ORDER BY housing_type_id")
                .param("row", merged.id()).query(Long.class).list())
                .containsExactly(type5198.getId(), type5991.getId(), type5993.getId());
        assertThat(jdbc.sql("SELECT count(*) FROM supply_row_groups").query(Long.class).single()).isZero();
    }

    @Test
    void 같은_작업_번호_재전송과_LH_재정제는_나눈_공급행과_관리자_값을_그대로_둔다() throws Exception {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = supplyCorrections.workspace(id);
        var request = request(workspace, splitRows(workspace.rows().getFirst()));
        var preview = supplyCorrections.preview(id, request);
        var apply = new SupplyCorrection.Apply(request, preview.confirmationToken());
        var first = supplyCorrections.apply(id, apply, "operator");
        var saved = rowValues(id);

        var again = supplyCorrections.apply(id, apply, "operator");

        assertThat(again.alreadyApplied()).isTrue();
        assertThat(again.changeId()).isEqualTo(first.changeId());
        assertThat(rowValues(id)).isEqualTo(saved);
        // PDF 보정 경로는 재전송을 저장값 충돌로 거절하고 아무것도 더 만들지 않는다.
        var pdfWorkspace = jsonCorrections.workspace(id);
        var pdf = jsonRequest(pdfWorkspace, pdfWorkspace.supply().rows().stream()
                .map(row -> edit(row, row.id(), row.key(), row.typeName(), null, row.householdCount() + 1, null, null)
                        .withLinkedHousingTypes(null)).toList());
        var pdfPreview = jsonCorrections.preview(id, pdf);
        var pdfApply = new AnnouncementJsonCorrection.Apply(pdf, pdfPreview.confirmationToken());
        jsonCorrections.apply(id, pdfApply, "operator");
        var pdfSaved = rowValues(id);
        assertThatThrownBy(() -> jsonCorrections.apply(id, pdfApply, "operator")).isInstanceOf(AdminDataConflictException.class);
        assertThat(rowValues(id)).isEqualTo(pdfSaved);

        supplies(supply("공공임대", "", "241"));
        refinement.refineAll();

        assertThat(rowValues(id)).isEqualTo(pdfSaved);
        assertThat(supplyOwner(id)).isEqualTo("ANNOUNCEMENT_DOCUMENT");
        assertThat(jdbc.sql("SELECT supply_recheck_required FROM announcements WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void 분리_저장이_중간에_실패하면_통합행도_바뀌지_않고_오래된_작업실_토큰은_거절한다() {
        long id = refinedAnnouncement(supply("공공임대", "", "240"));
        var workspace = supplyCorrections.workspace(id);
        var request = request(workspace, splitRows(workspace.rows().getFirst()));
        var preview = supplyCorrections.preview(id, request);
        var before = rowValues(id);
        // 마지막 분리행이 쓸 원천 식별자를 통합행이 먼저 차지하게 해, 앞 행을 쓴 뒤 그 행을 넣는 단계에서 실패하게 한다.
        jdbc.sql("UPDATE supply_rows SET source_supply_row_identifier = :identifier WHERE announcement_id = :id")
                .param("identifier", "MANUAL:" + request.operationId() + ":split-15").param("id", id).update();

        assertThatThrownBy(() -> supplyCorrections.apply(id, new SupplyCorrection.Apply(request, preview.confirmationToken()),
                "operator")).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rowValues(id)).isEqualTo(before);
        assertThat(supplyOwner(id)).isEqualTo("LH_SOURCE");
        assertThat(jdbc.sql("SELECT count(*) FROM admin_supply_corrections").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes WHERE action = 'CORRECT_SUPPLY'").query(Long.class).single())
                .isZero();

        applySupply(id, request(workspace, List.of(edit(workspace.rows().getFirst(), workspace.rows().getFirst().id(),
                workspace.rows().getFirst().key(), "051.9800A", type5198, 240, null, null))));
        var stale = request(workspace, splitRows(workspace.rows().getFirst()));
        assertThatThrownBy(() -> supplyCorrections.preview(id, stale)).isInstanceOf(AdminDataStaleException.class);
        assertThat(rowCount(id)).isOne();
    }

    private List<Row> splitRows(Row merged) {
        return List.of(
                edit(merged, merged.id(), merged.key(), "051.9800A", type5198, 200, SaveMode.SPLIT, MERGED),
                edit(merged, null, "split-25", "059.9100A", type5991, 25, SaveMode.SPLIT, MERGED),
                edit(merged, null, "split-15", "059.9300B", type5993, 15, SaveMode.SPLIT, MERGED));
    }

    /** 기존 행 base의 단지·공급구분을 쓰는 요청 행. 기존 행은 공급대상을 그대로 두고 새 행은 공급대상 없이 만든다. */
    private Row edit(Row base, Long id, String key, String typeName, HousingType type, Integer count, SaveMode mode,
            String lhSource) {
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
                false, List.of(), mode, lhSource);
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
                request(workspace.supply(), rows), List.of());
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
                    coalesce(total_supply_household_count, -1), admin_modified, coalesce(lh_split_source_identifier, '')
                FROM supply_rows WHERE announcement_id = :id ORDER BY id""").param("id", id)
                .query((rs, index) -> List.<Object>of(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getInt(5), rs.getBoolean(6), rs.getString(7))).list();
    }

    private long rowCount(long id) {
        return jdbc.sql("SELECT count(*) FROM supply_rows WHERE announcement_id = :id").param("id", id).query(Long.class).single();
    }

    private int householdSum(long id) {
        return jdbc.sql("SELECT sum(total_supply_household_count) FROM supply_rows WHERE announcement_id = :id AND NOT admin_excluded")
                .param("id", id).query(Integer.class).single();
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
        lhStorage.replaceDetails(panId, request(panId).requestDescription(), List.of(
                LhSourceViews.detail(0, panId, new LhAnnouncementDetailSourceSnapshot("COMPLEX", COMPLEX_NAME, ADDRESS,
                        null, "1000", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null)),
                LhSourceViews.detail(1, panId, new LhAnnouncementDetailSourceSnapshot("SCHEDULE", COMPLEX_NAME, null,
                        null, null, null, null, null, null, null, start, end, winner, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null))));
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
