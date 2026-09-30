package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;

public record AttachmentContent(String fileName, TemporaryAttachment file) {
}
