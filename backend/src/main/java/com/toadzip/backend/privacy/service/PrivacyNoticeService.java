package com.toadzip.backend.privacy.service;

import com.toadzip.backend.privacy.domain.PrivacyNotice;
import com.toadzip.backend.privacy.dto.PrivacyNoticeResponse;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PrivacyNoticeService {

    private final PrivacyNoticeCatalog catalog;

    public List<PrivacyNoticeResponse> currentDocuments() {
        return catalog.currentDocuments().stream().map(document -> response(document, false)).toList();
    }

    public PrivacyNoticeResponse find(String key, String version) {
        return response(catalog.find(key, version), true);
    }

    private PrivacyNoticeResponse response(PrivacyNotice document, boolean includeContent) {
        String format = null;
        String content = null;
        if (includeContent) {
            format = "markdown";
            content = document.content();
        }
        return new PrivacyNoticeResponse(document.key(), document.version(), document.scopeVersion(),
                document.effectiveAt(), document.contentHash(),
                "/api/v1/privacy/notices/" + document.key() + "/" + document.version(), format, content);
    }
}
