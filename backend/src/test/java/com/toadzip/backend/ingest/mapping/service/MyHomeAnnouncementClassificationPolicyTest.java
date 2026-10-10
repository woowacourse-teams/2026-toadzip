package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MyHomeAnnouncementClassificationPolicyTest {

    private final MyHomeAnnouncementClassificationPolicy policy =
            new MyHomeAnnouncementClassificationPolicy(new MyHomeAnnouncementValueParser());

    @ParameterizedTest
    @ValueSource(strings = {"50년임대", "50년공공임대"})
    void 같은_공급유형의_별칭은_같은_단지와_공고_유형에_연결한다(String sourceType) {
        assertThat(policy.rentalType(sourceType)).isEqualTo(RentalType.PUBLIC_RENTAL_50Y);
        assertThat(policy.complexSupplyType(sourceType)).isEqualTo("PUBLIC_RENTAL_50Y");
    }

    @Test
    void 미지원_공급유형은_공고에서_기타로_분류해도_단지에_임의로_연결하지_않는다() {
        assertThat(policy.rentalType("미지원 유형")).isEqualTo(RentalType.ETC);
        assertThatThrownBy(() -> policy.complexSupplyType("미지원 유형"))
                .isInstanceOf(MyHomeAnnouncementMappingRejectedException.class)
                .hasMessage("지원하지 않는 단지 공급유형입니다: 미지원 유형");
    }
}
