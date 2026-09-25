package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdminAnnouncementImportJson {

    private static final int MAX_JSON_BYTES = 1_000_000;

    private final ObjectMapper objectMapper;

    public AdminAnnouncementImportJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(AdminAnnouncementImportRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalStateException("공고 가져오기 JSON을 직렬화할 수 없습니다.", exception);
        }
    }

    public String hash(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시를 계산할 수 없습니다.", exception);
        }
    }

    public boolean isWithinSizeLimit(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES;
    }
}
