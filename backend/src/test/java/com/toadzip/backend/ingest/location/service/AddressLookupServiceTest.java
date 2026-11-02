package com.toadzip.backend.ingest.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.location.exception.AddressLookupException;
import com.toadzip.backend.ingest.location.repository.external.JusoAddressClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddressLookupServiceTest {

    private final JusoAddressClient client = mock(JusoAddressClient.class);
    private final AddressLookupService service = new AddressLookupService(client, new UtmKCoordinateConverter());

    @Test
    void 검색_결과마다_PNU와_행정구역_코드와_WGS84_좌표를_채운다() {
        var withBuilding = new JusoAddress("인천광역시 부평구 부평대로 1", "인천광역시 부평구 부평동 65-17", "2823710100",
                "282373154001", false, 1, 0, false, 65, 17);
        var withoutCoordinate = new JusoAddress("", "인천광역시 부평구 부평동 산3", "2823710100", "", false, 0, 0, true, 3, 0);
        when(client.search("부평동 65-17", 5)).thenReturn(List.of(withBuilding, withoutCoordinate));
        when(client.coordinate(withBuilding)).thenReturn(Optional.of(new UtmKCoordinate(new BigDecimal("937500"), new BigDecimal("1943000"))));
        when(client.coordinate(withoutCoordinate)).thenReturn(Optional.empty());

        var result = service.lookup(" 부평동 65-17 ");

        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).satisfies(candidate -> {
            assertThat(candidate.pnu()).isEqualTo("2823710100100650017");
            assertThat(candidate.provinceCode()).isEqualTo("28");
            assertThat(candidate.cityCountyDistrictCode()).isEqualTo("28237");
            assertThat(candidate.latitude()).isBetween(new BigDecimal("37"), new BigDecimal("38"));
            assertThat(candidate.longitude()).isBetween(new BigDecimal("126"), new BigDecimal("127"));
        });
        assertThat(result.get(1).pnu()).isEqualTo("2823710100200030000");
        assertThat(result.get(1).latitude()).isNull();
    }

    @Test
    void 좌표_변환에_실패한_후보는_좌표만_비우고_나머지_후보와_함께_돌려준다() {
        var unconvertible = new JusoAddress("인천광역시 부평구 부평대로 1", "인천광역시 부평구 부평동 65-17", "2823710100",
                "282373154001", false, 1, 0, false, 65, 17);
        var convertible = new JusoAddress("인천광역시 부평구 부평대로 2", "인천광역시 부평구 부평동 65-18", "2823710100",
                "282373154001", false, 2, 0, false, 65, 18);
        when(client.search("부평대로", 5)).thenReturn(List.of(unconvertible, convertible));
        when(client.coordinate(unconvertible)).thenReturn(Optional.of(new UtmKCoordinate(
                new BigDecimal("1E+1000"), new BigDecimal("1E+1000"))));
        when(client.coordinate(convertible)).thenReturn(Optional.of(new UtmKCoordinate(
                new BigDecimal("937500"), new BigDecimal("1943000"))));

        var result = service.lookup("부평대로");

        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).satisfies(candidate -> {
            assertThat(candidate.roadAddress()).isEqualTo("인천광역시 부평구 부평대로 1");
            assertThat(candidate.pnu()).isEqualTo("2823710100100650017");
            assertThat(candidate.legalDongCode()).isEqualTo("2823710100");
            assertThat(candidate.latitude()).isNull();
            assertThat(candidate.longitude()).isNull();
        });
        assertThat(result.get(1).latitude()).isBetween(new BigDecimal("37"), new BigDecimal("38"));
    }

    @Test
    void 좌표_변환_실패가_아닌_좌표_조회_오류는_그대로_던진다() {
        var address = new JusoAddress("인천광역시 부평구 부평대로 1", "인천광역시 부평구 부평동 65-17", "2823710100",
                "282373154001", false, 1, 0, false, 65, 17);
        var failure = new AddressLookupException("주소 검색을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
        when(client.search("부평대로", 5)).thenReturn(List.of(address));
        when(client.coordinate(address)).thenThrow(failure);

        assertThatThrownBy(() -> service.lookup("부평대로")).isSameAs(failure);
    }

    @Test
    void 법정동코드가_없는_결과는_돌려주지_않는다() {
        when(client.search(any(), any(Integer.class))).thenReturn(List.of(new JusoAddress("", "", "", "", false, 0, 0, false, 0, 0)));

        assertThat(service.lookup("부평")).isEmpty();
    }
}
