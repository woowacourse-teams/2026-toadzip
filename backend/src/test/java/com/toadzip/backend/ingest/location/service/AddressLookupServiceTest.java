package com.toadzip.backend.ingest.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.domain.UtmKCoordinate;
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
    void 법정동코드가_없는_결과는_돌려주지_않는다() {
        when(client.search(any(), any(Integer.class))).thenReturn(List.of(new JusoAddress("", "", "", "", false, 0, 0, false, 0, 0)));

        assertThat(service.lookup("부평")).isEmpty();
    }
}
