package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import java.util.List;

/** 관리자 공고 상세. 공급정보는 공고 공급표(supplySheet)이고 공급 보정 작업대에서만 고친다. */
public record AdminAnnouncementDetail(AdminDataSummary summary, String sourceIdentifier,
        AdminAnnouncementUpdateRequest data, boolean scheduleReviewed, SupplySheetView supplySheet,
        List<VerifiedApplicationSchedulesRequest.Schedule> schedules, List<AnnouncementAttachmentResponse> attachments) {
}
