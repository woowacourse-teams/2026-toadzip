package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.domain.SupplySheetDraft;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Cell;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.HousingTypeRow;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Lease;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Quota;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Target;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공급표 쓰기 진입점이 draft 그대로 공급표를 쓰고, 다시 써도 ID를 지키며, 공개 상세가 그 공급표를 읽는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupplySheetStoreIntegrationTest {

    @Autowired private SupplySheetStore store;
    @Autowired private AnnouncementQueryService queries;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;

    private NotificationFixtures fixtures;
    private Announcement announcement;
    private AnnouncementComplex happyComplex;
    private HousingComplex housing;
    private HousingType type55;

    @BeforeEach
    void setUp() {
        fixtures = new NotificationFixtures(entities, jdbc);
        announcement = fixtures.announcement();
        housing = fixtures.complex("11140", "1114010100");
        type55 = HousingType.createFromMyHome(housing, UUID.randomUUID().toString(), "55A", new BigDecimal("55.1200"),
                null);
        entities.persist(type55);
        happyComplex = AnnouncementComplex.observe(announcement, "행복단지", null, null);
        entities.persist(happyComplex);
        entities.flush();
    }

    @Test
    void 새_공급표를_쓰면_주택형_대상_칸_묶음_임대조건이_draft대로_저장되고_정본이_확정된다() {
        assertThat(announcement.isSupplyConfirmed()).isFalse();
        SupplySheetStore.Saved saved = replace(sheet());

        assertThat(jdbc.queryForObject("SELECT supply_confirmed_at IS NOT NULL FROM announcements WHERE id = ?",
                Boolean.class, announcement.getId())).isTrue();
        assertThat(saved.housingTypes()).containsOnlyKeys("h1", "h2", "h3");
        assertThat(saved.cells()).containsOnlyKeys("c1", "c2", "c3", "c4", "c5");
        assertThat(rows("""
                SELECT housing_type_name || '/' || COALESCE(exclusive_area::text, '-') FROM supply_rows
                WHERE announcement_id = ? ORDER BY display_order""")).containsExactly("55A/55.1000", "55B/-", "84/-");
        assertThat(rows("""
                SELECT label || '/' || application_condition FROM announcement_supply_targets
                WHERE announcement_id = ? ORDER BY display_order""")).containsExactly("청년/", "신혼부부/소득 있음", "고령자/");
        assertThat(quotas()).containsExactlyInAnyOrder("WINNER:103:c1,c3", "RESERVE:20:c1,c2", "WINNER:5:c5");
        assertThat(leases()).containsExactlyInAnyOrder("100000000/200000:c1,c2,c3,c4", "null/50000:c5");
        assertThat(jdbc.queryForObject("SELECT admin_modified FROM lease_terms WHERE monthly_rent = 50000 AND announcement_id = ?",
                Boolean.class, announcement.getId())).isTrue();
    }

    @Test
    void 같은_draft를_다시_쓰면_공급행_대상_칸_ID가_그대로다() {
        SupplySheetStore.Saved first = replace(sheet());

        SupplySheetStore.Saved second = replace(sheet());

        assertThat(second.housingTypes()).isEqualTo(first.housingTypes());
        assertThat(second.targets()).isEqualTo(first.targets());
        assertThat(second.cells()).isEqualTo(first.cells());
        assertThat(quotas()).containsExactlyInAnyOrder("WINNER:103:c1,c3", "RESERVE:20:c1,c2", "WINNER:5:c5");
    }

    @Test
    void 기존_공급행은_자연_키로_찾아_고치고_공급표가_다루지_않는_열은_그대로_둔다() {
        SupplyRow stored = SupplyRowSeed.create(entities, announcement, null, null, 3, "행복단지2", "84", null, null,
                SupplyCategory.NEW_SUPPLY);
        entities.persist(stored);
        entities.flush();
        jdbc.update("UPDATE supply_rows SET admin_modified = true WHERE id = ?", stored.getId());
        entities.clear();

        SupplySheetStore.Saved saved = replace(sheet());

        assertThat(saved.housingTypes().get("h3")).isEqualTo(stored.getId());
        SupplyRow row = entities.find(SupplyRow.class, stored.getId());
        assertThat(row.isAdminModified()).isTrue();
        assertThat(row.getHousingTypeName()).isEqualTo("84");
        assertThat(row.getDisplayOrder()).isEqualTo(2);
        // 공급표에는 draft의 칸만 남는다.
        assertThat(rows("""
                SELECT st.label FROM supply_row_targets c JOIN announcement_supply_targets st ON st.id = c.supply_target_id
                JOIN supply_rows r ON r.id = c.supply_row_id WHERE r.announcement_id = ? AND r.housing_type_name = '84'
                """)).containsExactly("고령자");
    }

    @Test
    void 기존_주택형은_같은_공고_단지의_같은_신청형_이름일_때만_고친다() {
        SupplyRow same = SupplyRowSeed.create(entities, announcement, null, null, 0, "행복단지", "26A", null, null,
                SupplyCategory.NEW_SUPPLY);
        SupplyRow otherComplex = SupplyRowSeed.create(entities, announcement, null, null, 1, "행복단지2", "26A", null,
                null, SupplyCategory.NEW_SUPPLY);
        entities.persist(same);
        entities.persist(otherComplex);
        entities.flush();

        SupplySheetStore.Saved saved = replace(single(named("26A")));

        assertThat(saved.housingTypes().get("h1")).isEqualTo(same.getId());
        assertThat(count("SELECT count(*) FROM supply_rows WHERE id = ?", otherComplex.getId())).isZero();
    }

    @Test
    void 주택형과_대상을_빼면_그_칸과_수량_묶음과_임대조건이_함께_지워진다() {
        SupplySheetStore.Saved first = replace(sheet());

        SupplySheetStore.Saved second = replace(SupplySheetDraft.of(
                List.of(type55A(), type55B()), List.of(youth(), newlywed()),
                List.of(new Cell("c1", "h1", "t1", 0), new Cell("c2", "h1", "t2", 1), new Cell("c3", "h2", "t1", 0),
                        new Cell("c4", "h2", "t2", 1)),
                List.of(new Quota(Kind.WINNER, 103, List.of("c1", "c3"), false)),
                List.of(new Lease(amounts("100000000", "200000"), List.of("c1", "c2", "c3", "c4"), false))));

        assertThat(count("SELECT count(*) FROM supply_rows WHERE id = ?", first.housingTypes().get("h3"))).isZero();
        assertThat(count("SELECT count(*) FROM announcement_supply_targets WHERE id = ?", first.targets().get("t3"))).isZero();
        assertThat(count("SELECT count(*) FROM supply_row_targets WHERE id = ?", first.cells().get("c5"))).isZero();
        assertThat(count("SELECT count(*) FROM supply_quota_members WHERE supply_row_target_id = ?", first.cells().get("c5")))
                .isZero();
        assertThat(count("SELECT count(*) FROM lease_term_members WHERE supply_row_target_id = ?", first.cells().get("c5")))
                .isZero();
        assertThat(quotas()).containsExactly("WINNER:103:c1,c3");
        assertThat(leases()).containsExactly("100000000/200000:c1,c2,c3,c4");
        assertThat(second.cells().get("c1")).isEqualTo(first.cells().get("c1"));
    }

    @Test
    void 다른_공고의_공급행_ID를_가리키면_거부한다() {
        Announcement other = fixtures.announcement();
        SupplyRow foreign = SupplyRowSeed.create(entities, other, null, null, 0, "다른 단지", "36A", null,
                null, SupplyCategory.NEW_SUPPLY);
        entities.persist(foreign);
        entities.flush();
        HousingTypeRow pointing = new HousingTypeRow("h1", foreign.getId(), null, null, null, "다른 단지", "36A", null,
                null, null, null, SupplyCategory.NEW_SUPPLY, 0);

        assertThatThrownBy(() -> replace(SupplySheetDraft.of(List.of(pointing), List.of(youth()),
                List.of(new Cell("c1", "h1", "t1", 0)), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이 공고의 공급행이 아니다");
    }

    @Test
    void 공개_상세는_쓴_공급표의_행과_함께_쓰는_수와_임대조건을_보인다() {
        SupplySheetStore.Saved saved = replace(sheet());
        entities.clear();

        AnnouncementDetailResponse detail = queries.getAnnouncement(announcement.getId());

        assertThat(detail.supplyRows()).extracting(SupplyRowResponse::supplyRowId).containsExactly(
                saved.housingTypes().get("h1"), saved.housingTypes().get("h2"), saved.housingTypes().get("h3"));
        SupplyRowResponse first = detail.supplyRows().getFirst();
        assertThat(first.sharedRecruitment().householdCount()).isEqualTo(103);
        assertThat(first.exclusiveArea()).isEqualByComparingTo("55.10");
        SupplyTargetResponse youthCell = first.targets().getFirst();
        assertThat(youthCell.supplyTargetId()).isEqualTo(saved.cells().get("c1"));
        assertThat(youthCell.sharedWaitlistCount().count()).isEqualTo(20);
        assertThat(youthCell.sharedWaitlistCount().targets()).containsExactly("청년", "신혼부부");
        assertThat(youthCell.deposit()).isEqualTo(100_000_000L);
        assertThat(first.targets().get(1).applicationCondition()).isEqualTo("소득 있음");
        assertThat(detail.supplyRows().get(2).totalSupplyHouseholdCount()).isEqualTo(5);
        assertThat(detail.supplyRows().get(2).targets().getFirst().monthlyRent()).isEqualTo(50_000L);
    }

    private static HousingTypeRow named(String name) {
        return new HousingTypeRow("h1", null, null, null, null, "행복단지", name, null, null, null, null,
                SupplyCategory.NEW_SUPPLY, 0);
    }

    private static SupplySheetDraft single(HousingTypeRow type) {
        return SupplySheetDraft.of(List.of(type), List.of(youth()), List.of(new Cell("c1", "h1", "t1", 0)), List.of(),
                List.of());
    }

    private SupplySheetStore.Saved replace(SupplySheetDraft draft) {
        SupplySheetStore.Saved saved = store.replace(announcement, draft);
        entities.flush();
        return saved;
    }

    /**
     * 행복단지 55A·55B(공고 단지 연결)와 단지명만 아는 행복단지2 84. 55A·55B의 청년이 당첨 103을 함께 쓰고(여러 주택형 묶음),
     * 55A의 청년·신혼부부가 예비 20을 함께 쓰며, 55A·55B의 네 칸이 임대조건 하나를 함께 쓴다.
     */
    private SupplySheetDraft sheet() {
        return SupplySheetDraft.of(
                List.of(type55A(), type55B(), new HousingTypeRow("h3", null, null, null, null, "행복단지2", "84", null,
                        null, null, null, SupplyCategory.NEW_SUPPLY, 2)),
                List.of(youth(), newlywed(), new Target("t3", "고령자", null, 2, false)),
                List.of(new Cell("c1", "h1", "t1", 0), new Cell("c2", "h1", "t2", 1), new Cell("c3", "h2", "t1", 0),
                        new Cell("c4", "h2", "t2", 1), new Cell("c5", "h3", "t3", 0)),
                List.of(new Quota(Kind.WINNER, 103, List.of("c1", "c3"), false),
                        new Quota(Kind.RESERVE, 20, List.of("c1", "c2"), false),
                        new Quota(Kind.WINNER, 5, List.of("c5"), false)),
                List.of(new Lease(amounts("100000000", "200000"), List.of("c1", "c2", "c3", "c4"), false),
                        new Lease(new LeaseTerms.Amounts(null, new BigDecimal("50000"), null), List.of("c5"), true)));
    }

    private HousingTypeRow type55A() {
        return new HousingTypeRow("h1", null, happyComplex.getId(), housing.getId(), type55.getId(), "행복단지", "55A",
                new BigDecimal("55.10"), null, null, null, SupplyCategory.NEW_SUPPLY, 0);
    }

    private HousingTypeRow type55B() {
        return new HousingTypeRow("h2", null, happyComplex.getId(), housing.getId(), null, "행복단지", "55B", null, null,
                null, null, SupplyCategory.NEW_SUPPLY, 1);
    }

    private static Target youth() {
        return new Target("t1", "청년", null, 0, false);
    }

    private static Target newlywed() {
        return new Target("t2", "신혼부부", "소득 있음", 1, false);
    }

    private static LeaseTerms.Amounts amounts(String deposit, String rent) {
        return new LeaseTerms.Amounts(new BigDecimal(deposit), new BigDecimal(rent), null);
    }

    /** 수량 묶음을 "종류:수:칸 키들"로. 칸 키는 표기·신청형 이름으로 다시 붙인다(ID는 실행마다 다르다). */
    private List<String> quotas() {
        return rows("""
                SELECT q.kind || ':' || q.household_count || ':' || string_agg(%s, ',' ORDER BY %s)
                FROM supply_quotas q JOIN supply_quota_members m ON m.supply_quota_id = q.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                WHERE q.announcement_id = ? GROUP BY q.id, q.kind, q.household_count
                """.formatted(cellKey(), cellKey()));
    }

    private List<String> leases() {
        return rows("""
                SELECT COALESCE(l.rental_deposit::bigint::text, 'null') || '/' || l.monthly_rent::bigint || ':'
                       || string_agg(%s, ',' ORDER BY %s)
                FROM lease_terms l JOIN lease_term_members m ON m.lease_terms_id = l.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                WHERE l.announcement_id = ? GROUP BY l.id
                """.formatted(cellKey(), cellKey()));
    }

    /** 이 테스트의 칸 키(c1~c5)를 (신청형 이름, 공급대상 표기)로 되찾는다. */
    private static String cellKey() {
        return """
                (SELECT CASE r.housing_type_name || '/' || st.label
                            WHEN '55A/청년' THEN 'c1' WHEN '55A/신혼부부' THEN 'c2' WHEN '55B/청년' THEN 'c3'
                            WHEN '55B/신혼부부' THEN 'c4' WHEN '84/고령자' THEN 'c5' END
                 FROM supply_rows r, announcement_supply_targets st
                 WHERE r.id = c.supply_row_id AND st.id = c.supply_target_id)""";
    }

    private List<String> sheetSnapshot() {
        List<String> cells = rows("""
                SELECT c.id || ':' || c.supply_row_id || ':' || c.supply_target_id FROM supply_row_targets c
                JOIN supply_rows r ON r.id = c.supply_row_id WHERE r.announcement_id = ? ORDER BY c.id""");
        List<String> members = rows("""
                SELECT q.id || ':' || q.kind || ':' || q.household_count || ':' || m.supply_row_target_id
                FROM supply_quotas q JOIN supply_quota_members m ON m.supply_quota_id = q.id
                WHERE q.announcement_id = ? ORDER BY q.id, m.supply_row_target_id""");
        return java.util.stream.Stream.concat(cells.stream(), members.stream()).toList();
    }

    private List<String> rows(String sql) {
        return jdbc.queryForList(sql, String.class, announcement.getId());
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }
}
