package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementDetail;
import com.toadzip.backend.announcement.service.AdminAnnouncementManagementService;
import com.toadzip.backend.housing.dto.request.AdminHousingTypeUpdateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexDetail;
import com.toadzip.backend.housing.service.AdminHousingComplexManagementService;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
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
        var sheet = announcement.supplySheet();
        var cell = sheet.cells().stream().filter(item -> key.equals(item.key())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 공고에 속한 공급대상 칸을 선택해 주세요."));
        var current = cellValues(sheet, cell);
        return new Target(current, new LinkedHashMap<>(current));
    }

    /** 칸의 공급대상과 그 칸만의 모집·예비 수, 임대조건 금액. 공급 검토 기록의 "cell:{id}" 값과 같은 항목이다. */
    private static Map<String, Object> cellValues(SupplySheetView sheet, SupplySheetView.Cell cell) {
        var target = sheet.targets().stream().filter(item -> item.key().equals(cell.target())).findFirst().orElseThrow();
        var lease = sheet.leaseTerms().stream().filter(item -> item.members().contains(cell.key())).findFirst().orElse(null);
        var values = new LinkedHashMap<String, Object>();
        values.put("label", target.label());
        values.put("applicationCondition", target.applicationCondition());
        values.put("winnerCount", ownCount(sheet, cell, SupplyQuota.Kind.WINNER));
        values.put("reserveCount", ownCount(sheet, cell, SupplyQuota.Kind.RESERVE));
        values.put("rentalDeposit", lease == null ? null : text(lease.rentalDeposit()));
        values.put("monthlyRent", lease == null ? null : text(lease.monthlyRent()));
        values.put("convertedDeposit", lease == null ? null : text(lease.convertedDeposit()));
        return values;
    }

    private static Integer ownCount(SupplySheetView sheet, SupplySheetView.Cell cell, SupplyQuota.Kind kind) {
        return sheet.quotas().stream().filter(quota -> quota.kind() == kind && quota.members().equals(List.of(cell.key())))
                .map(SupplySheetView.Quota::count).findFirst().orElse(null);
    }

    private static String text(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
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
        if (key.startsWith("cell:")) {
            throw new IllegalArgumentException("공급정보는 공급 보정 작업대에서 고쳐 주세요.");
        }
        var body = new LinkedHashMap<>(before.body());
        body.putAll(values);
        if (resource == QualityResource.COMPLEX) {
            if (!key.startsWith("type:")) {
                throw new IllegalArgumentException("단지 기본정보는 단지 비교 화면에서 수정해 주세요.");
            }
            complexes.updateHousingType(id, targetId(key), validated(body, AdminHousingTypeUpdateRequest.class), actor);
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
