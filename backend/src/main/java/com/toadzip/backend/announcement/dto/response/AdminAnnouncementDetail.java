package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import java.util.List;

public record AdminAnnouncementDetail(AdminDataSummary summary, String sourceIdentifier,
        AdminAnnouncementUpdateRequest data, boolean scheduleReviewed, List<SupplyRowItem> supplyRows,
        List<com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest.Schedule> schedules,
        List<SupplyTargetItem> supplyTargets) {
    public record SupplyRowItem(long id, Long housingComplexId, String housingComplexName,
            Long housingTypeId, boolean modified, SupplyRowRequest data) { }
    public record SupplyTargetItem(long id, long supplyRowId, SupplyTargetData data) { }
    public record SupplyTargetData(String target, String supplyRank, Integer supplyHouseholdCount, Integer reserveCount,
            String rentalDeposit, String monthlyRent, String convertedDeposit, String applicationCondition) { }
}
