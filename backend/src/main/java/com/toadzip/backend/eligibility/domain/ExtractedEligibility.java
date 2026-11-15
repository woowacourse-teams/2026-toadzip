package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 워커가 공고 PDF 신청자격 장에서 뽑은 요건(추출 결과의 data.eligibility). 숫자는 워커가 근거 인용과 대조해 남긴 값만 있다.
 * evidencePath는 같은 추출 결과의 근거 목록(evidence)에서 이 항목의 근거를 찾는 경로다.
 */
public record ExtractedEligibility(
        Group common, List<Route> routes, List<Exclusion> exclusions, Map<String, Quote> quotes) {
    public ExtractedEligibility {
        routes = EligibilityContent.immutable(routes);
        exclusions = EligibilityContent.immutable(exclusions);
        // 근거 ID 순서와 '묶음의 첫 근거'가 실행마다 같도록 원래 순서를 지킨다.
        quotes = Collections.unmodifiableMap(new LinkedHashMap<>(quotes));
    }

    /** 공고 전체(common) 또는 경로 하나에 걸린 요건과, 그중 하나만 갖추면 되는 직접 확인 자격. */
    public record Group(String evidencePath, List<Requirement> requirements, List<Qualification> qualifications) {
        public Group {
            requirements = EligibilityContent.immutable(requirements);
            qualifications = EligibilityContent.immutable(qualifications);
        }
    }

    /**
     * 신청 자격 경로. supplyTargets는 워커가 맞춘 공급표 대상 이름 원문이다. supplyTargetKeys는 워커가 이은 공급표 대상 키를 같은 추출의
     * 공급표(data.supplySheet.targets)에서 찾은 (표기, 신청 조건)이다. 키가 있으면 공급대상은 이 키로만 잇는다. groups는 워커가 경로
     * 이름(없으면 소속 대상)에서 읽은 공급대상 분류로, 키도 이름도 공급대상에 맞지 않을 때 쓴다.
     */
    public record Route(String name, List<String> supplyTargets, List<EligibilityContent.SupplyTarget> supplyTargetKeys,
            List<SupplyTargetGroup> groups, Group group) {
        public Route {
            supplyTargets = EligibilityContent.immutable(supplyTargets);
            supplyTargetKeys = EligibilityContent.immutable(supplyTargetKeys);
            groups = EligibilityContent.immutable(groups);
        }

        /** 분류가 없는 경로(분류를 읽기 전 워커의 추출). */
        public Route(String name, List<String> supplyTargets, List<EligibilityContent.SupplyTarget> supplyTargetKeys,
                Group group) {
            this(name, supplyTargets, supplyTargetKeys, List.of(), group);
        }

        /** 공급표 대상 키가 없는 경로(이전 워커의 추출, 워커가 대상 이름을 맞추지 못한 경로). */
        public Route(String name, List<String> supplyTargets, Group group) {
            this(name, supplyTargets, List.of(), group);
        }
    }

    public record Requirement(
            String kind,
            String scope,
            Integer minimum,
            Integer maximum,
            Integer minimumMonths,
            String incomeStandard,
            Integer incomeBaseYear,
            Integer percent,
            Map<Long, Integer> householdSizeExtraPercent,
            List<Adjustment> adjustments,
            Long maximumKrw,
            List<String> regions,
            List<String> familyTypes,
            List<Requirement> options,
            String asWritten,
            String evidencePath) {
        public Requirement {
            householdSizeExtraPercent = Map.copyOf(householdSizeExtraPercent);
            adjustments = EligibilityContent.immutable(adjustments);
            regions = EligibilityContent.immutable(regions);
            familyTypes = EligibilityContent.immutable(familyTypes);
            options = EligibilityContent.immutable(options);
        }
    }

    /** 출산자녀·맞벌이·장애인 등 가산. asWritten은 가산 원문(예: "출산자녀 2인 이상 20% 가산, 출산자녀 1인 10% 가산")이며 없을 수 있다. */
    public record Adjustment(String kind, String asWritten) {}

    public record Qualification(String exactName, String legalBasis, String conditionsAsWritten, String evidencePath) {}

    public record Exclusion(String title, String body, String evidencePath) {}

    public record Quote(int page, String quote) {}
}
