package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import java.util.List;

public record AdminAnnouncementDetail(AdminDataSummary summary, String sourceIdentifier,
        AdminAnnouncementUpdateRequest data, boolean scheduleReviewed, List<SupplyRowItem> supplyRows,
        List<com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest.Schedule> schedules) {
    public record SupplyRowItem(long id, Long housingComplexId, String housingComplexName,
            Long housingTypeId, boolean modified, SupplyRowRequest data) { }
}
