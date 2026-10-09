package com.toadzip.backend.privacy.dto;

public record PrivacyChoiceRequest(String commandId, String expectedUserId, String contextId, Long expectedRevision,
        String action, String noticeVersion, String scopeVersion, String source) {
}
