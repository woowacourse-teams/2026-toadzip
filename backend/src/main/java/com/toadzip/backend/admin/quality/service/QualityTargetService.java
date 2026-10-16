package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminSupplyRowUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminSupplyTargetUpdateRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementDetail;
import com.toadzip.backend.announcement.service.AdminAnnouncementManagementService;
import com.toadzip.backend.housing.dto.request.AdminHousingTypeUpdateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexDetail;
import com.toadzip.backend.housing.service.AdminHousingComplexManagementService;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class QualityTargetService {
    private final AdminHousingComplexManagementService complexes;
    private final AdminAnnouncementManagementService announcements;
    private final ObjectMapper json;
    private final Validator validator;

    public QualityTargetService(AdminHousingComplexManagementService complexes,
            AdminAnnouncementManagementService announcements, ObjectMapper json, Validator validator) {
        this.complexes = complexes;
        this.announcements = announcements;
        this.json = json;
        this.validator = validator;
    }

    public Object detail(QualityResource resource, long id) {
        if (resource == QualityResource.COMPLEX) {
            return complexes.detail(id);
        }
        return announcements.detail(id);
    }

    public Target target(Object detail, String key) {
        if (detail instanceof AdminHousingComplexDetail complex) {
            if (complex.summary().deleted()) {
                throw new AdminDataConflictException("휴지통에서 복구한 뒤 검토해 주세요.");
            }
            if (key.equals("basic")) {
                return new Target(map(complex.data()), map(complex.data()));
            }
            var type = complex.housingTypes().stream().filter(item -> key.equals("type:" + item.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("이 단지에 속한 주택형을 선택해 주세요."));
            var current = map(type);
            current.remove("id");
            var body = new LinkedHashMap<>(current);
            body.put("version", complex.data().version());
            return new Target(current, body);
        }
        var announcement = (AdminAnnouncementDetail) detail;
        if (announcement.summary().deleted()) {
            throw new AdminDataConflictException("휴지통에서 복구한 뒤 검토해 주세요.");
        }
        if (key.equals("basic")) {
            return new Target(map(announcement.data()), map(announcement.data()));
        }
        for (var row : announcement.supplyRows()) {
            if (key.equals("row:" + row.id())) {
                var body = new LinkedHashMap<String, Object>();
                body.put("version", announcement.data().version());
                body.put("housingComplexId", row.housingComplexId());
                body.put("housingTypeId", row.housingTypeId());
                body.put("supplyRow", map(row.data()));
                return new Target(map(row.data()), body);
            }
        }
        var condition = announcement.supplyTargets().stream().filter(item -> key.equals("target:" + item.id()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("이 공고에 속한 공급대상을 선택해 주세요."));
        var current = map(condition.data());
        var body = new LinkedHashMap<>(current);
        body.put("version", announcement.data().version());
        body.put("expectedValues", condition.data());
        return new Target(current, body);
    }

    public void update(QualityResource resource, long id, String key, Target before,
            Map<String, Object> patch, String actor) {
        var values = new LinkedHashMap<>(before.current());
        for (var entry : patch.entrySet()) {
            if (!values.containsKey(entry.getKey()) || entry.getKey().equals("version")
                    || entry.getKey().equals("documentEvidence")) {
                throw new IllegalArgumentException("수정할 수 없는 항목입니다: " + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue());
        }
        var body = new LinkedHashMap<>(before.body());
        if (key.startsWith("row:")) {
            body.put("supplyRow", values);
            announcements.updateRow(id, targetId(key), validated(body, AdminSupplyRowUpdateRequest.class), actor);
            return;
        }
        body.putAll(values);
        if (resource == QualityResource.COMPLEX) {
            if (!key.startsWith("type:")) {
                throw new IllegalArgumentException("단지 기본정보는 단지 비교 화면에서 수정해 주세요.");
            }
            complexes.updateHousingType(id, targetId(key), validated(body, AdminHousingTypeUpdateRequest.class), actor);
            return;
        }
        if (key.startsWith("target:")) {
            announcements.updateTarget(id, targetId(key), validated(body, AdminSupplyTargetUpdateRequest.class), actor);
            return;
        }
        announcements.update(id, validated(body, AdminAnnouncementUpdateRequest.class), actor);
    }

    private long targetId(String key) {
        return Long.parseLong(key.substring(key.indexOf(':') + 1));
    }

    private <T> T validated(Map<String, Object> body, Class<T> type) {
        T request;
        try {
            request = json.convertValue(body, type);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("수정값의 형식·단위를 확인해 주세요.");
        }
        var errors = validator.validate(request);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.iterator().next().getPropertyPath() + ": "
                    + errors.iterator().next().getMessage());
        }
        return request;
    }

    private Map<String, Object> map(Object value) {
        return json.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    public record Target(Map<String, Object> current, Map<String, Object> body) { }
}
