package com.toadzip.backend.housing.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.housing.dto.response.HousingTypeImpact;
import com.toadzip.backend.housing.repository.HousingTypeImpactRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class HousingTypeImpactService {
    private final HousingTypeRepository types;
    private final HousingTypeImpactRepository impacts;
    private final ObjectMapper json;

    public HousingTypeImpact detail(long complexId, long typeId) {
        var type = types.findById(typeId).orElseThrow(() -> new AdminDataConflictException("주택형을 찾을 수 없습니다."));
        if (type.getHousingComplex().getId() != complexId) {
            throw new IllegalArgumentException("이 단지에 속한 주택형을 선택하세요.");
        }
        var affected = impacts.rows(typeId);
        var values = new LinkedHashMap<String, Object>();
        values.put("name", type.getName()); values.put("area", type.getExclusiveArea());
        String token = hash(List.of(typeId, values, type.getHousingComplex().getVersion(), affected));
        return new HousingTypeImpact(token, affected);
    }

    public void check(long complexId, long typeId, String expectedToken, boolean areaChanges) {
        var impact = detail(complexId, typeId);
        if (expectedToken != null && !impact.token().equals(expectedToken)) {
            throw new AdminDataConflictException("연결 공고나 주택형이 변경되었습니다. 수정 영향을 다시 확인하세요.");
        }
        // 공급행은 (단지, 전용면적)으로 주택형을 찾으므로 면적을 바꾸면 연결된 공급행의 매칭이 어긋난다.
        if (areaChanges && !impact.rows().isEmpty()) {
            throw new AdminDataConflictException("공고 공급행이 연결된 주택형은 전용면적을 바꿀 수 없습니다. 올바른 면적의 주택형을 새로 만들고 공급행을 옮긴 뒤 이 주택형을 휴지통에 넣으세요.");
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
