package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SupplyRowSheetValuesTest {

    private static final String PNU = "1111010100100010000";

    @Test
    void 공급표가_정한_신청형_이름과_면적이_주택형_값이다() {
        Announcement announcement = new Announcement();
        AnnouncementComplex complex = AnnouncementComplex.observe(announcement, "평화주공1단지", null, null);
        HousingComplex housing = mock(HousingComplex.class);
        HousingType type = type(housing);

        SupplyRow row = SupplyRow.createFromSheet(announcement, values("26형 고령자(주거약자용)", new BigDecimal("26.1"),
                null), complex, housing, type);

        assertThat(row.getHousingTypeName()).isEqualTo("26형 고령자(주거약자용)");
        assertThat(row.getExclusiveArea()).isEqualByComparingTo("26.1");
        assertThat(row.getSupplyArea()).isNull();
        assertThat(row.getAnnouncementComplex()).isSameAs(complex);
    }

    @Test
    void 공급표가_면적을_주지_않으면_연결_주택타입의_면적을_주택형_면적으로_쓴다() {
        Announcement announcement = new Announcement();
        AnnouncementComplex complex = AnnouncementComplex.observe(announcement, "평화주공1단지", null, null);
        HousingComplex housing = mock(HousingComplex.class);

        SupplyRow linked = SupplyRow.createFromSheet(announcement, values("26", null, null), complex, housing,
                type(housing));
        SupplyRow unlinked = SupplyRow.createFromSheet(announcement, values("26", null, null), complex, null, null);

        assertThat(linked.getExclusiveArea()).isEqualByComparingTo("26.4673");
        assertThat(linked.getSupplyArea()).isEqualByComparingTo("35.1");
        assertThat(unlinked.getExclusiveArea()).isNull();
        assertThat(unlinked.getSupplyArea()).isNull();
    }

    @Test
    void 주택형은_이_공고의_공고_단지에만_든다() {
        Announcement announcement = new Announcement();
        AnnouncementComplex other = AnnouncementComplex.observe(new Announcement(), "평화주공1단지", null, null);

        assertThatThrownBy(() -> SupplyRow.createFromSheet(announcement, values("26", null, null), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplyRow.createFromSheet(announcement, values("26", null, null), other, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SupplySheetDraft.HousingTypeRow values(String name, BigDecimal exclusiveArea,
            BigDecimal supplyArea) {
        return new SupplySheetDraft.HousingTypeRow("h1", null, null, null, null, "평화주공1단지", name, exclusiveArea,
                supplyArea, null, PNU, SupplyCategory.NEW_SUPPLY, 1);
    }

    private static HousingType type(HousingComplex housing) {
        HousingType type = mock(HousingType.class);
        when(type.getHousingComplex()).thenReturn(housing);
        when(type.getExclusiveArea()).thenReturn(new BigDecimal("26.4673"));
        when(type.getSupplyArea()).thenReturn(new BigDecimal("35.1"));
        return type;
    }
}
