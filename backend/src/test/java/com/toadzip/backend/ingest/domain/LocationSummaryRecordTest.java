package com.toadzip.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LocationSummaryRecordTest {

    @Test
    void 위치정보요약_필드로_조회용_도로명주소를_구성한다() {
        LocationSummaryRecord record = record("성산읍", "1", 42, 3);

        assertThat(record.roadAddress()).isEqualTo("제주특별자치도 서귀포시 성산읍 일출로 지하 42-3");
        assertThat(record.normalizedRoadAddress()).isEqualTo(record.roadAddress());
        assertThat(record.provinceCode()).isEqualTo("50");
        assertThat(record.hasCoordinate()).isTrue();
    }

    @Test
    void 동_이름은_도로명주소에_포함하지_않는다() {
        assertThat(record("태평로1가", "0", 110, 0).roadAddress())
                .isEqualTo("제주특별자치도 서귀포시 일출로 110");
    }

    @Test
    void 좌표는_둘_다_있거나_둘_다_없어야_한다() {
        assertThatThrownBy(() -> new LocationSummaryRecord(
                "50130", "1", "5013010100", "제주특별자치도", "서귀포시", "",
                "501302000001", "일출로", "0", 1, 0, BigDecimal.ONE, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("함께");
    }

    private LocationSummaryRecord record(String townName, String underground, int main, int sub) {
        return new LocationSummaryRecord(
                "50130", "1", "5013010100", "제주특별자치도", "서귀포시", townName,
                "501302000001", "일출로", underground, main, sub,
                new BigDecimal("906000.123456"), new BigDecimal("1480000.123456")
        );
    }
}
