package com.toadzip.backend.privacy.domain;

import com.toadzip.backend.privacy.exception.PrivacyException;
import java.time.Instant;

/** Each collection requires an unexpired grant for the current processing scope. */
public class AnalyticsCollectionPolicy {

    public boolean isAllowed(AnalyticsConsent consent, String requiredScope, Instant now) {
        return consent != null && requiredScope != null && consent.getExpiresAt() != null
                && consent.effectiveStatus(requiredScope, now) == ConsentStatus.GRANTED;
    }

    public void requireAllowed(AnalyticsConsent consent, String requiredScope, Instant now) {
        if (!isAllowed(consent, requiredScope, now)) {
            throw new PrivacyException("ANALYTICS_CONSENT_REQUIRED", "이용 정보 분석 허용을 확인할 수 없습니다.");
        }
    }
}
