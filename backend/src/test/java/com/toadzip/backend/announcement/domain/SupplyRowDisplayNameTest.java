package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.HousingType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SupplyRowDisplayNameTest {

    private static final String PNU = "1111010100100010000";

    @Test
    void 공고_표시명이_없으면_연결_주택형명을_없으면_원천_주택형명을_보인다() {
        assertThat(supplyRow(type("26")).displayHousingTypeName()).isEqualTo("26");
        assertThat(supplyRow(null).displayHousingTypeName()).isEqualTo("39.8800");
    }

    @Test
    void 공급표가_정한_신청형_이름은_원천_이름을_바꾸지_않고_공고_표시명으로_남긴다() {
        SupplyRow row = supplyRow(null);
        ReflectionTestUtils.setField(row.getAnnouncement(), "id", 1L);

        row.applySheet(new SupplySheetDraft.HousingTypeRow("h1", null, null, null, null, "평화주공1단지",
                "26형 고령자(주거약자용)", null, null, null, PNU, SupplyCategory.NEW_SUPPLY, 1), null, null, null);

        assertThat(row.getSourceHousingTypeName()).isEqualTo("39.8800");
        assertThat(row.getAnnouncementHousingTypeName()).isEqualTo("26형 고령자(주거약자용)");
        assertThat(row.displayHousingTypeName()).isEqualTo("26형 고령자(주거약자용)");
    }

    private static HousingType type(String name) {
        HousingType type = mock(HousingType.class);
        when(type.getName()).thenReturn(name);
        return type;
    }

    private static SupplyRow supplyRow(HousingType type) {
        return SupplyRow.create(new Announcement(), null, type, "5:195851:2", 1, "평화주공1단지", "39.8800",
                PNU, null, SupplyCategory.NEW_SUPPLY, null);
    }
}
