package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.SupplyTargetCountGroup.Kind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SupplyTargetCountGroupTest {

    @Test
    void 대상끼리_함께_쓰는_공급세대수는_묶음마다_한_번만_더하고_제외한_대상은_빼고_더한다() {
        var row = row();
        var shared = SupplyTargetCountGroup.create(row, Kind.SUPPLY, 39);
        ReflectionTestUtils.setField(shared, "id", 1L);
        var withoutIncome = target(row, "청년(소득 없음)", null, 0);
        var withIncome = target(row, "청년(소득 있음)", null, 1);
        withoutIncome.shareCounts(shared, null);
        withIncome.shareCounts(shared, null);
        var elder = target(row, "고령자", 2, 2);
        var excluded = target(row, "대학생", 5, 3);
        excluded.correctPresentation(true, 3);

        assertThat(SupplyTargetCountGroup.householdTotal(List.of(withoutIncome, withIncome, elder, excluded))).isEqualTo(41);
    }

    @Test
    void 묶음과_같은_종류에_자기_수가_있거나_다른_종류_묶음이면_넣지_않는다() {
        var row = row();
        var supply = SupplyTargetCountGroup.create(row, Kind.SUPPLY, 39);

        assertThatThrownBy(() -> target(row, "청년", 3, 0).shareCounts(supply, null))
                .hasMessageContaining("그 대상만의 모집수를 넣을 수 없습니다");
        assertThatThrownBy(() -> target(row, "청년", null, 0).shareCounts(null, supply))
                .hasMessageContaining("같은 공급행·종류의 공통 모집수에만");
    }

    private static SupplyTarget target(SupplyRow row, String name, Integer count, int order) {
        return SupplyTarget.create(row, name, null, count, null, null, null, null, null, order);
    }

    private static SupplyRow row() {
        var date = LocalDate.of(2026, 11, 5);
        var announcement = Announcement.create("source", null, null, "평택송화 국민임대", "원공고", "국민임대", "예비입주자",
                "LH", date, date, date.plusDays(2), date.plusDays(30), "https://example.com/notice", null, 0L, null, null,
                List.of());
        return SupplyRow.create(announcement, null, null, "row-1", 0, "평택송화", "36", null, null,
                SupplyCategory.NEW_SUPPLY, null, 39);
    }
}
