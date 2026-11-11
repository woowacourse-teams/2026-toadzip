package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationInformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 수집 시각과 행 순서에 영향받지 않는 원천 내용 검증과 보완값 유효성 확인을 맡는다. */
@Component
@RequiredArgsConstructor
public class InitialRegistrationInputs {
    private final ObjectMapper json;

    public String fingerprint(List<MyHomeComplexSourceView> rows) {
        var snapshots = rows.stream().map(row -> json.writeValueAsString(row.snapshot())).sorted().toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(snapshots).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public boolean hasInformation(InitialRegistrationInformation information) {
        return information != null && information.getFingerprint() != null;
    }
}
