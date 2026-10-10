package com.toadzip.backend.privacy.domain;

import com.toadzip.backend.privacy.exception.PrivacyException;
import java.util.UUID;

public record ConsentCommand(UUID commandId, String expectedUserId, UUID contextId, long expectedRevision,
        ConsentAction action, String noticeVersion, String scopeVersion, ConsentSource source) {

    public String fingerprint() {
        return PrivacyHash.sha256(encode(expectedUserId) + encode(contextId) + encode(expectedRevision)
                + encode(action) + encode(noticeVersion) + encode(scopeVersion) + encode(source));
    }

    private String encode(Object value) {
        if (value == null) {
            return "-1:";
        }
        String text = value.toString();
        return text.length() + ":" + text;
    }

    public void verifyRevision(long revision) {
        if (revision != expectedRevision) {
            throw new PrivacyException("PRIVACY_REVISION_CONFLICT", "선택이 변경되었습니다. 현재 상태를 확인해 주세요.");
        }
    }
}
