package com.toadzip.backend.housing.dto.response;

import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import java.math.BigDecimal;
import java.util.List;

public record AdminHousingComplexDetail(AdminDataSummary summary, String sourceIdentifier,
        AdminHousingComplexUpdateRequest data, List<HousingTypeItem> housingTypes,
        List<AdminDataSummary> announcements) {
    public record HousingTypeItem(long id, String name, BigDecimal exclusiveArea, Integer householdCount) { }
}
