package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.AttachmentType;

public record AnnouncementAttachmentResponse(
        long attachmentId,
        String fileName,
        AttachmentType fileType,
        String fileUrl
) {
}
