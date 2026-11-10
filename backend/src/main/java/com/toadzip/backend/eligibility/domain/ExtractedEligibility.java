package com.toadzip.backend.eligibility.domain;

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
        quotes = Map.copyOf(quotes);
    }

    /** 공고 전체(common) 또는 경로 하나에 걸린 요건과, 그중 하나만 갖추면 되는 직접 확인 자격. */
    public record Group(String evidencePath, List<Requirement> requirements, List<Qualification> qualifications) {
        public Group {
            requirements = EligibilityContent.immutable(requirements);
            qualifications = EligibilityContent.immutable(qualifications);
        }
    }

    /** 신청 자격 경로. supplyTargets는 워커가 맞춘 공급표 대상 이름 원문이다. */
    public record Route(String name, List<String> supplyTargets, Group group) {
        public Route {
            supplyTargets = EligibilityContent.immutable(supplyTargets);
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
            List<String> adjustments,
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

    public record Qualification(String exactName, String legalBasis, String conditionsAsWritten, String evidencePath) {}

    public record Exclusion(String title, String body, String evidencePath) {}

    public record Quote(int page, String quote) {}
}
