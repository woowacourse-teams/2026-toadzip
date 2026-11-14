package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.SupplySheetDraft;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import java.util.List;
import java.util.Map;

/**
 * 공급표 보정 요청을 저장된 공급표에 맞춘 결과. after는 저장할 공급표이고, 저장된 개체에 맞춘 개체는 보기 key("row:{id}" 등)를,
 * 새 개체는 요청 key를 쓴다. housingTypeKeys는 요청 주택형 key → after 주택형 key, targetAdminModified는 after 공급대상 key별 관리자
 * 수정 표시다(보기에는 싣지 않는다). changed가 false면 저장할 공급표가 지금과 같다.
 */
public record SupplySheetPlan(SupplySheetView before, SupplySheetView after, Map<String, String> housingTypeKeys,
        Map<String, Boolean> targetAdminModified, List<String> warnings, List<AnnouncementComplexAdminLinks.Plan> links,
        List<Object> candidates, boolean changed) {

    public SupplySheetPlan {
        housingTypeKeys = Map.copyOf(housingTypeKeys);
        targetAdminModified = Map.copyOf(targetAdminModified);
        warnings = List.copyOf(warnings);
        links = List.copyOf(links);
        candidates = List.copyOf(candidates);
    }

    /** 요청 주택형 key가 가리키는 저장할 주택형. 없으면 null이다. */
    public SupplySheetView.HousingType housingType(String inputKey) {
        String key = housingTypeKeys.get(inputKey);
        if (key == null) { return null; }
        return after.housingTypes().stream().filter(type -> type.key().equals(key)).findFirst().orElseThrow();
    }

    /** 저장할 공급표. createdTypeIds는 after 주택형 key별로 이 저장에서 만든 단지 주택형 ID다(미리보기 검증에서는 비어 있다). */
    SupplySheetDraft draft(Map<String, Long> createdTypeIds) {
        return SupplySheetDraft.of(
                after.housingTypes().stream().map(type -> new SupplySheetDraft.HousingTypeRow(type.key(), type.id(),
                        type.announcementComplexId(), type.housingComplexId(),
                        type.housingTypeId() != null ? type.housingTypeId() : createdTypeIds.get(type.key()),
                        type.complexName(), type.name(), type.exclusiveArea(), type.supplyArea(), type.expectedMoveInMonth(),
                        type.supplyPnu(), type.category(), type.displayOrder())).toList(),
                after.targets().stream().map(target -> new SupplySheetDraft.Target(target.key(), target.label(),
                        target.applicationCondition(), target.displayOrder(),
                        targetAdminModified.getOrDefault(target.key(), false))).toList(),
                after.cells().stream().map(cell -> new SupplySheetDraft.Cell(cell.key(), cell.housingType(), cell.target(),
                        cell.displayOrder())).toList(),
                after.quotas().stream().map(quota -> new SupplySheetDraft.Quota(quota.kind(), quota.count(), quota.members(),
                        quota.adminModified())).toList(),
                after.leaseTerms().stream().map(lease -> new SupplySheetDraft.Lease(new LeaseTerms.Amounts(lease.rentalDeposit(),
                        lease.monthlyRent(), lease.convertedDeposit()), lease.members(), lease.adminModified())).toList());
    }
}
