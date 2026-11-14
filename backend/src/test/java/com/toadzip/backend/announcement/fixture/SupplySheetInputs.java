package com.toadzip.backend.announcement.fixture;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.dto.correction.SupplySheetInput;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 공급표 보정 요청을 만드는 테스트 도구. 저장된 공급표를 그대로 보내는 요청에서 필요한 곳만 바꾼다. */
public final class SupplySheetInputs {
    private SupplySheetInputs() { }

    /** 화면처럼 보기 JSON을 그대로 보내는 요청 JSON. 요청에 없는 보기 필드(서버가 정하는 값)만 뗀다. */
    public static ObjectNode json(JsonNode view) {
        var sheet = (ObjectNode) view.deepCopy();
        sheet.withArray("housingTypes").forEach(type -> ((ObjectNode) type).remove(List.of("housingComplexName", "adminModified")));
        for (var name : List.of("quotas", "leaseTerms")) {
            sheet.withArray(name).forEach(item -> ((ObjectNode) item).remove(List.of("key", "id", "adminModified")));
        }
        return sheet;
    }

    /** 보기 그대로의 요청. 그대로 보내면 공급표가 바뀌지 않는다. */
    public static SupplySheetInput of(SupplySheetView view) {
        return new SupplySheetInput(view.housingTypes().stream().map(SupplySheetInputs::housingType).toList(),
                view.targets().stream().map(target -> new SupplySheetInput.Target(target.key(), target.id(), target.label(),
                        target.applicationCondition(), target.displayOrder())).toList(),
                view.cells().stream().map(cell -> new SupplySheetInput.Cell(cell.key(), cell.id(), cell.housingType(),
                        cell.target(), cell.displayOrder())).toList(),
                view.quotas().stream().map(quota -> new SupplySheetInput.Quota(quota.kind(), quota.count(), quota.members()))
                        .toList(),
                view.leaseTerms().stream().map(lease -> new SupplySheetInput.Lease(lease.rentalDeposit(), lease.monthlyRent(),
                        lease.convertedDeposit(), lease.members())).toList());
    }

    public static SupplySheetInput.HousingType housingType(SupplySheetView.HousingType type) {
        return new SupplySheetInput.HousingType(type.key(), type.id(), type.announcementComplexId(), type.complexName(),
                type.housingComplexId(), type.housingTypeId(), type.newHousingType(), type.name(), type.exclusiveArea(),
                type.supplyArea(), type.expectedMoveInMonth(), type.supplyPnu(), type.category(), type.displayOrder());
    }

    /** 단지·주택형을 정하지 않은 새 주택형. */
    public static SupplySheetInput.HousingType newHousingType(String key, Long announcementComplexId, String complexName,
            Long housingComplexId, String name, BigDecimal exclusiveArea, int displayOrder) {
        return new SupplySheetInput.HousingType(key, null, announcementComplexId, complexName, housingComplexId, null, null,
                name, exclusiveArea, null, null, null, SupplyCategory.NEW_SUPPLY, displayOrder);
    }

    /** 주택형 하나의 값을 바꾼다. */
    public static SupplySheetInput withHousingType(SupplySheetInput input, String key,
            UnaryOperator<SupplySheetInput.HousingType> change) {
        return new SupplySheetInput(input.housingTypes().stream().map(type -> type.key().equals(key) ? change.apply(type) : type)
                .toList(), input.targets(), input.cells(), input.quotas(), input.leaseTerms());
    }

    public static SupplySheetInput.HousingType renamed(SupplySheetInput.HousingType type, String name) {
        return new SupplySheetInput.HousingType(type.key(), type.id(), type.announcementComplexId(), type.complexName(),
                type.housingComplexId(), type.housingTypeId(), type.newHousingType(), name, type.exclusiveArea(),
                type.supplyArea(), type.expectedMoveInMonth(), type.supplyPnu(), type.category(), type.displayOrder());
    }

    public static SupplySheetInput.HousingType linked(SupplySheetInput.HousingType type, Long housingComplexId,
            Long housingTypeId, SupplySheetInput.NewHousingType newHousingType, BigDecimal exclusiveArea) {
        return new SupplySheetInput.HousingType(type.key(), type.id(), type.announcementComplexId(), type.complexName(),
                housingComplexId, housingTypeId, newHousingType, type.name(), exclusiveArea, type.supplyArea(),
                type.expectedMoveInMonth(), type.supplyPnu(), type.category(), type.displayOrder());
    }

    /** members 칸들의 kind 수를 count로 바꾼다. 그 묶음이 없으면 더한다. */
    public static SupplySheetInput withQuota(SupplySheetInput input, SupplyQuota.Kind kind, int count, String... members) {
        List<SupplySheetInput.Quota> quotas = new ArrayList<>(input.quotas().stream().filter(quota -> quota.kind() != kind
                || !quota.members().equals(List.of(members))).toList());
        quotas.add(new SupplySheetInput.Quota(kind, count, List.of(members)));
        return new SupplySheetInput(input.housingTypes(), input.targets(), input.cells(), quotas, input.leaseTerms());
    }

    /** 주택형과 그 칸·묶음 멤버를 뺀다. 멤버가 비는 묶음도 뺀다. */
    public static SupplySheetInput without(SupplySheetInput input, String housingTypeKey) {
        var cellKeys = input.cells().stream().filter(cell -> cell.housingType().equals(housingTypeKey))
                .map(SupplySheetInput.Cell::key).toList();
        return new SupplySheetInput(input.housingTypes().stream().filter(type -> !type.key().equals(housingTypeKey)).toList(),
                input.targets(), input.cells().stream().filter(cell -> !cellKeys.contains(cell.key())).toList(),
                input.quotas().stream().map(quota -> new SupplySheetInput.Quota(quota.kind(), quota.count(),
                        quota.members().stream().filter(member -> !cellKeys.contains(member)).toList()))
                        .filter(quota -> !quota.members().isEmpty()).toList(),
                input.leaseTerms().stream().map(lease -> new SupplySheetInput.Lease(lease.rentalDeposit(), lease.monthlyRent(),
                        lease.convertedDeposit(), lease.members().stream().filter(member -> !cellKeys.contains(member)).toList()))
                        .filter(lease -> !lease.members().isEmpty()).toList());
    }

    /** 주택형을 더한다. 공급대상 칸은 붙이지 않는다. */
    public static SupplySheetInput plus(SupplySheetInput input, SupplySheetInput.HousingType... types) {
        var housingTypes = new ArrayList<>(input.housingTypes());
        housingTypes.addAll(List.of(types));
        return new SupplySheetInput(housingTypes, input.targets(), input.cells(), input.quotas(), input.leaseTerms());
    }

    /** 주택형에 공급대상 칸 하나와 그 칸만의 당첨 수를 더한다. 공급대상이 요청에 없으면 함께 더한다. */
    public static SupplySheetInput plusCell(SupplySheetInput input, String cellKey, String housingTypeKey,
            SupplySheetInput.Target target, int winnerCount) {
        var targets = new ArrayList<>(input.targets());
        if (targets.stream().noneMatch(stored -> stored.key().equals(target.key()))) { targets.add(target); }
        var cells = new ArrayList<>(input.cells());
        cells.add(new SupplySheetInput.Cell(cellKey, null, housingTypeKey, target.key(), 0));
        var quotas = new ArrayList<>(input.quotas());
        quotas.add(new SupplySheetInput.Quota(SupplyQuota.Kind.WINNER, winnerCount, List.of(cellKey)));
        return new SupplySheetInput(input.housingTypes(), targets, cells, quotas, input.leaseTerms());
    }
}
