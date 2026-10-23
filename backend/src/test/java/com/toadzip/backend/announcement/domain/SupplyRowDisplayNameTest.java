package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.HousingType;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class SupplyRowDisplayNameTest {

    private static final String PNU = "1111010100100010000";

    @Test
    void 관리자_수정은_원천_이름을_바꾸지_않고_공고_표시명으로_남긴다() {
        SupplyRow row = supplyRow(type("26"));

        row.reviseByAdmin(null, type("26"), "26형 고령자(주거약자용)", PNU, null, SupplyCategory.NEW_SUPPLY, 20);

        assertThat(row.getSourceComplexName()).isEqualTo("평화주공1단지");
        assertThat(row.getSourceHousingTypeName()).isEqualTo("39.8800");
        assertThat(row.getAnnouncementHousingTypeName()).isEqualTo("26형 고령자(주거약자용)");
        assertThat(row.displayHousingTypeName()).isEqualTo("26형 고령자(주거약자용)");
    }

    @Test
    void 기본_표시와_같거나_빈_표기는_공고_표시명으로_저장하지_않는다() {
        SupplyRow row = supplyRow(type("26"));

        row.reviseByAdmin(null, type("26"), " 26 ", PNU, null, SupplyCategory.NEW_SUPPLY, 20);
        assertThat(row.getAnnouncementHousingTypeName()).isNull();

        row.reviseByAdmin(null, type("26"), " ", PNU, null, SupplyCategory.NEW_SUPPLY, 20);
        assertThat(row.getAnnouncementHousingTypeName()).isNull();
        assertThat(row.displayHousingTypeName()).isEqualTo("26");
    }

    @Test
    void 연결_주택형이_없으면_원천_이름과_같은_표기를_저장하지_않고_원천_이름을_보여_준다() {
        SupplyRow row = supplyRow(null);

        row.reviseByAdmin(null, null, "39.8800", PNU, null, SupplyCategory.NEW_SUPPLY, 20);

        assertThat(row.getAnnouncementHousingTypeName()).isNull();
        assertThat(row.displayHousingTypeName()).isEqualTo("39.8800");
    }

    @Test
    void 관리자_수정_행도_정제가_원천_이름은_갱신하고_관리자_값은_보호한다() {
        SupplyRow row = supplyRow(type("26"));
        row.reviseByAdmin(null, type("26"), "26.37", PNU, YearMonth.of(2027, 1), SupplyCategory.RESUPPLY, 7);

        boolean updated = row.updateFromMyHome(null, null, 3, "전주평화1단지", "아파트", "2222020200200020000",
                SupplyCategory.NEW_SUPPLY, "매칭 실패", 450);

        // 원천 이름은 바꾸지만 공고 값 변경으로 세지 않아 정제 중 LH 재보강을 일으키지 않는다.
        assertThat(updated).isFalse();
        assertThat(row.getSourceComplexName()).isEqualTo("전주평화1단지");
        assertThat(row.getSourceHousingTypeName()).isEqualTo("아파트");
        assertThat(row.getAnnouncementHousingTypeName()).isEqualTo("26.37");
        assertThat(row.getHousingType()).isNotNull();
        assertThat(row.getSupplyPnu()).isEqualTo(PNU);
        assertThat(row.getSupplyCategory()).isEqualTo(SupplyCategory.RESUPPLY);
        assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(7);
        assertThat(row.getMatchingFailureReason()).isNull();
    }

    private static HousingType type(String name) {
        HousingType type = mock(HousingType.class);
        when(type.getName()).thenReturn(name);
        return type;
    }

    private static SupplyRow supplyRow(HousingType type) {
        return SupplyRow.create(new Announcement(), null, type, "5:195851:2", 1, "평화주공1단지", "39.8800",
                PNU, null, SupplyCategory.NEW_SUPPLY, null, 20);
    }
}
