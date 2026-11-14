package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.SupplyQuota.Kind;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Cell;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.HousingTypeRow;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Lease;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Quota;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Target;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplySheetDraftTest {

    @Test
    void 주택형_대상_칸_묶음_임대조건이_서로_맞으면_만든다() {
        SupplySheetDraft draft = SupplySheetDraft.of(List.of(type("h1", null), type("h2", null)),
                List.of(target("t1", "청년", null)),
                List.of(new Cell("c1", "h1", "t1", 0), new Cell("c2", "h2", "t1", 0)),
                List.of(new Quota(Kind.WINNER, 103, List.of("c1", "c2"), false)),
                List.of(new Lease(amounts("1000"), List.of("c1", "c2"), true)));

        assertThat(draft.cells()).hasSize(2);
        assertThat(draft.targets().getFirst().applicationCondition()).isEmpty();
        assertThat(draft.leases().getFirst().adminModified()).isTrue();
    }

    @Test
    void 칸이_모르는_주택형이나_공급대상을_가리키면_거부한다() {
        assertThatThrownBy(() -> draft(List.of(new Cell("c1", "h9", "t1", 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸의 주택형");
        assertThatThrownBy(() -> draft(List.of(new Cell("c1", "h1", "t9", 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸의 공급대상");
    }

    @Test
    void 수량_묶음이나_임대조건이_모르는_칸을_가리키면_거부한다() {
        assertThatThrownBy(() -> draft(oneCell(), List.of(new Quota(Kind.WINNER, 1, List.of("c9"), false)), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("수량 묶음의 칸");
        assertThatThrownBy(() -> draft(oneCell(), List.of(), List.of(new Lease(amounts("1"), List.of("c9"), false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("임대조건의 칸");
    }

    @Test
    void 키가_겹치면_거부한다() {
        assertThatThrownBy(() -> SupplySheetDraft.of(List.of(type("h1", null), type("h1", null)),
                List.of(target("t1", "청년", null)), List.of(new Cell("c1", "h1", "t1", 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("주택형이 겹친다");
        assertThatThrownBy(() -> draft(List.of(new Cell("c1", "h1", "t1", 0), new Cell("c1", "h1", "t1", 1)),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸이 겹친다");
    }

    @Test
    void 같은_주택형과_공급대상의_칸이_두_번이면_거부한다() {
        assertThatThrownBy(() -> draft(List.of(new Cell("c1", "h1", "t1", 0), new Cell("c2", "h1", "t1", 1)),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸 (주택형, 공급대상)");
    }

    @Test
    void 표기와_신청_조건이_같은_공급대상이_둘이면_거부한다() {
        assertThatThrownBy(() -> SupplySheetDraft.of(List.of(type("h1", null)),
                List.of(target("t1", "청년", null), target("t2", " 청년 ", "")),
                List.of(new Cell("c1", "h1", "t1", 0), new Cell("c2", "h1", "t2", 1)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공급대상 (표기, 신청 조건)");
    }

    @Test
    void 같은_공급행_ID를_두_주택형이_가리키면_거부한다() {
        assertThatThrownBy(() -> SupplySheetDraft.of(List.of(type("h1", 7L), type("h2", 7L)),
                List.of(target("t1", "청년", null)), List.of(new Cell("c1", "h1", "t1", 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공급행 ID");
    }

    @Test
    void 칸이_없는_공급대상은_거부한다() {
        assertThatThrownBy(() -> SupplySheetDraft.of(List.of(type("h1", null)),
                List.of(target("t1", "청년", null), target("t2", "고령자", null)),
                List.of(new Cell("c1", "h1", "t1", 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸이 없는 공급대상");
    }

    @Test
    void 칸_하나가_임대조건_둘에_속하면_거부한다() {
        assertThatThrownBy(() -> draft(oneCell(), List.of(), List.of(new Lease(amounts("1"), List.of("c1"), false),
                new Lease(amounts("2"), List.of("c1"), false))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("임대조건 하나만");
    }

    @Test
    void 수량_묶음은_모집수가_음수이거나_멤버가_없거나_겹치면_거부한다() {
        assertThatThrownBy(() -> new Quota(Kind.WINNER, -1, List.of("c1"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("음수");
        assertThatThrownBy(() -> new Quota(Kind.RESERVE, 1, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸이 하나 이상");
        assertThatThrownBy(() -> new Quota(Kind.RESERVE, 1, List.of("c1", "c1"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("겹친다");
    }

    @Test
    void 임대조건은_금액이_없거나_음수이거나_멤버가_없으면_거부한다() {
        assertThatThrownBy(() -> new Lease(new LeaseTerms.Amounts(null, null, null), List.of("c1"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("금액이 하나 이상");
        assertThatThrownBy(() -> new Lease(amounts("-1"), List.of("c1"), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("음수");
        assertThatThrownBy(() -> new Lease(amounts("1"), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("칸이 하나 이상");
    }

    @Test
    void 공급대상_표기가_비면_거부한다() {
        assertThatThrownBy(() -> target("t1", " ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공급대상 표기");
    }

    @Test
    void 주택형은_신청형_이름과_공급구분이_필요하고_단지_없이_주택형만_연결할_수_없다() {
        assertThatThrownBy(() -> new HousingTypeRow("h1", null, null, null, null, "단지", " ", null, null, null, null,
                SupplyCategory.NEW_SUPPLY, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("신청형 이름");
        assertThatThrownBy(() -> new HousingTypeRow("h1", null, null, null, null, "단지", "55A", null, null, null, null,
                null, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공급구분");
        assertThatThrownBy(() -> new HousingTypeRow("h1", null, null, null, 3L, "단지", "55A", null, null, null, null,
                SupplyCategory.NEW_SUPPLY, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단지도 연결");
    }

    private static SupplySheetDraft draft(List<Cell> cells, List<Quota> quotas, List<Lease> leases) {
        return SupplySheetDraft.of(List.of(type("h1", null)), List.of(target("t1", "청년", null)), cells, quotas, leases);
    }

    private static List<Cell> oneCell() {
        return List.of(new Cell("c1", "h1", "t1", 0));
    }

    private static HousingTypeRow type(String key, Long supplyRowId) {
        return new HousingTypeRow(key, supplyRowId, null, null, null, "행복단지", key, null, null, null, null,
                SupplyCategory.NEW_SUPPLY, 0);
    }

    private static Target target(String key, String label, String condition) {
        return new Target(key, label, condition, 0, false);
    }

    private static LeaseTerms.Amounts amounts(String deposit) {
        return new LeaseTerms.Amounts(new BigDecimal(deposit), null, null);
    }
}
