package com.toadzip.backend.announcement.dto.response;

import java.util.List;

/**
 * 공급대상 하나. sharedSupplyHouseholdCount·sharedWaitlistCount는 같은 공급행의 다른 대상과 함께 쓰는 모집수·예비자 수다(없으면 null).
 * 함께 쓰는 수는 대상마다 나누지 않으므로, 묶인 대상의 supplyHouseholdCount·waitlistCount는 비어 있다.
 */
public record SupplyTargetResponse(
        long supplyTargetId,
        String target,
        String priority,
        Integer supplyHouseholdCount,
        Integer waitlistCount,
        Long deposit,
        Long monthlyRent,
        Long convertibleDeposit,
        String applicationCondition,
        SharedCount sharedSupplyHouseholdCount,
        SharedCount sharedWaitlistCount
) {
    /** 함께 쓰는 수와 그 수를 함께 쓰는 대상 이름(공고 표시 순서). */
    public record SharedCount(int count, List<String> targets) {
        public SharedCount {
            targets = List.copyOf(targets);
        }
    }
}
