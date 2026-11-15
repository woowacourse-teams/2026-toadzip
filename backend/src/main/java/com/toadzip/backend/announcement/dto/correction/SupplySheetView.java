package com.toadzip.backend.announcement.dto.correction;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 관리자 화면이 읽는 공고 공급표. 주택형·공급대상·칸은 key로 서로를 가리키고, 수량 묶음과 임대조건은 칸 key를 멤버로 가진다.
 * 저장된 개체의 key는 "row:{id}"·"target:{id}"·"cell:{id}"·"quota:{id}"·"lease:{id}"이고, 미리보기의 새 개체는 요청 key("new:*")를 그대로 쓴다.
 * 수를 읽는 규칙은 {@link com.toadzip.backend.announcement.domain.SupplySheet}와 같다. 관리자가 제외한 옛 공급행은 보이지 않는다.
 */
public record SupplySheetView(List<HousingType> housingTypes, List<Target> targets, List<Cell> cells,
        List<Quota> quotas, List<Lease> leaseTerms) {

    public SupplySheetView {
        housingTypes = List.copyOf(housingTypes);
        targets = List.copyOf(targets);
        cells = List.copyOf(cells);
        quotas = List.copyOf(quotas);
        leaseTerms = List.copyOf(leaseTerms);
    }

    /**
     * 공고 주택형(신청형) 한 줄. complexName은 공고문 단지명, name은 신청형 이름이다. 면적은 공고가 따로 정했을 때만 있다.
     * newHousingType은 미리보기에서 이 저장이 단지에 새로 만들 주택형이고, 저장된 공급표에는 없다.
     */
    public record HousingType(String key, Long id, Long announcementComplexId, String complexName, Long housingComplexId,
            String housingComplexName, Long housingTypeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) SupplySheetInput.NewHousingType newHousingType, String name,
            BigDecimal exclusiveArea, BigDecimal supplyArea, YearMonth expectedMoveInMonth, String supplyPnu,
            SupplyCategory category, int displayOrder, boolean adminModified) {
    }

    /** 공급대상. 신청 조건이 없으면 빈 문자열이다. groups는 공급대상 분류(상수 순서)다. */
    public record Target(String key, Long id, String label, String applicationCondition, List<SupplyTargetGroup> groups,
            int displayOrder) {

        public Target {
            groups = List.copyOf(groups);
        }
    }

    /** 이 주택형에 이 공급대상이 있다는 칸. */
    public record Cell(String key, Long id, String housingType, String target, int displayOrder) {
    }

    public record Quota(String key, Long id, SupplyQuota.Kind kind, int count, List<String> members,
            boolean adminModified) {

        public Quota {
            members = List.copyOf(members);
        }
    }

    /** 금액은 원 단위 정수 문자열이다. */
    public record Lease(String key, Long id, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rentalDeposit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyRent,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal convertedDeposit, List<String> members,
            boolean adminModified) {

        public Lease {
            members = List.copyOf(members);
        }
    }
}
