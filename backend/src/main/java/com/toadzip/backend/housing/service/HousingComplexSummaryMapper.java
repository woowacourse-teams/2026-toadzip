package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;

@Component
public class HousingComplexSummaryMapper {

    private final HousingComplexCodeMapper codeMapper;

    public HousingComplexSummaryMapper(HousingComplexCodeMapper codeMapper) {
        this.codeMapper = codeMapper;
    }

    public HousingComplexMapItemResponse toMapItem(ComplexSummaryRow row) {
        return new HousingComplexMapItemResponse(
                row.complexId(),
                row.name(),
                row.latitude(),
                row.longitude(),
                codeMapper.toRentalType(row.rentalType()),
                codeMapper.toAgency(row.agencyCode()),
                row.exclusiveAreaMin(),
                row.exclusiveAreaMax(),
                toLongExact(row.depositMin()),
                toLongExact(row.depositMax()),
                toLongExact(row.monthlyRentMin()),
                toLongExact(row.monthlyRentMax())
        );
    }

    private Long toLongExact(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.longValueExact();
    }
}
