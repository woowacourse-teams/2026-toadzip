package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.AddressRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.BuildingType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.CorridorType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.HeatingType;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexCreateResponse;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminHousingComplexRegistrationServiceTest {

    @Test
    void 수기_원천_식별자와_기존_영문_코드로_단지를_저장한다() {
        HousingComplexRepository repository = mock(HousingComplexRepository.class);
        HousingComplexSourceIdentifierGenerator identifierGenerator = mock(
                HousingComplexSourceIdentifierGenerator.class
        );
        AdminHousingComplexRegistrationService service = new AdminHousingComplexRegistrationService(
                repository,
                identifierGenerator,
                (provinceCode, cityCountyDistrictCode) -> Optional.of("서울특별시 중구")
        );
        HousingComplex saved = mock(HousingComplex.class);
        when(identifierGenerator.generate()).thenReturn(
                "ADMIN_ENTRY-HOUSING-COMPLEX-123e4567-e89b-12d3-a456-426614174000"
        );
        when(repository.save(any())).thenReturn(saved);
        when(saved.getId()).thenReturn(42L);
        when(saved.getName()).thenReturn("두꺼비 행복주택");

        AdminHousingComplexCreateResponse response = service.register(request());

        ArgumentCaptor<HousingComplex> captor = ArgumentCaptor.forClass(HousingComplex.class);
        verify(repository).save(captor.capture());
        HousingComplex housingComplex = captor.getValue();
        assertAll(
                () -> assertEquals(42L, response.housingComplexId()),
                () -> assertEquals("두꺼비 행복주택", response.name()),
                () -> assertEquals("서울특별시 중구 세종대로 110", response.roadAddress()),
                () -> assertEquals(
                        "ADMIN_ENTRY-HOUSING-COMPLEX-123e4567-e89b-12d3-a456-426614174000",
                        housingComplex.getSourceComplexIdentifier()
                ),
                () -> assertEquals("HAPPY_HOUSING", housingComplex.getSupplyType()),
                () -> assertEquals("LH", housingComplex.getProvider()),
                () -> assertEquals("INDIVIDUAL", housingComplex.getHeatingType()),
                () -> assertEquals("APARTMENT", housingComplex.getHousingType()),
                () -> assertEquals("STAIR", housingComplex.getCorridorType())
        );
    }

    @Test
    void 해석할_수_없는_지역코드_조합은_저장하지_않는다() {
        HousingComplexRepository repository = mock(HousingComplexRepository.class);
        HousingComplexSourceIdentifierGenerator identifierGenerator = mock(
                HousingComplexSourceIdentifierGenerator.class
        );
        RegionCodeResolver regionCodeResolver = (provinceCode, cityCountyDistrictCode) -> Optional.empty();
        AdminHousingComplexRegistrationService service = new AdminHousingComplexRegistrationService(
                repository,
                identifierGenerator,
                regionCodeResolver
        );

        assertThrows(InvalidRegionCodeException.class, () -> service.register(request()));

        verifyNoInteractions(repository, identifierGenerator);
    }

    private AdminHousingComplexCreateRequest request() {
        AddressRequest address = new AddressRequest(
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "11140",
                new BigDecimal("37.566500"),
                new BigDecimal("126.978000")
        );
        return new AdminHousingComplexCreateRequest(
                "두꺼비 행복주택",
                RentalType.HAPPY_HOUSING,
                AgencyCode.LH,
                address,
                0,
                LocalDate.of(2020, 6, 30),
                HeatingType.INDIVIDUAL,
                BuildingType.APARTMENT,
                CorridorType.STAIR,
                true,
                0,
                "https://example.com/complex.png",
                0
        );
    }
}
