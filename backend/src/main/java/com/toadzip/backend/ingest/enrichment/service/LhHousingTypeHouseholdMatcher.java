package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LhHousingTypeHouseholdMatcher {

    private static final Map<String, String> SUPPLY_TYPES = Map.ofEntries(
            Map.entry("행복주택", "HAPPY_HOUSING"),
            Map.entry("국민임대", "NATIONAL_RENTAL"),
            Map.entry("국민임대주택", "NATIONAL_RENTAL"),
            Map.entry("영구임대", "PERMANENT_RENTAL"),
            Map.entry("영구임대주택", "PERMANENT_RENTAL"),
            Map.entry("5년임대", "PUBLIC_RENTAL"),
            Map.entry("공공임대5년", "PUBLIC_RENTAL"),
            Map.entry("10년임대", "PUBLIC_RENTAL"),
            Map.entry("공공임대10년", "PUBLIC_RENTAL"),
            Map.entry("50년임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("공공임대50년", "PUBLIC_RENTAL_50Y"),
            Map.entry("장기전세", "LONG_TERM_JEONSE"),
            Map.entry("통합공공임대", "INTEGRATED_PUBLIC_RENTAL"),
            Map.entry("재개발임대", "REDEVELOPMENT_RENTAL")
    );
    private static final Set<String> PUBLIC_RENTAL_TYPES = Set.of(
            "PUBLIC_RENTAL", "PUBLIC_RENTAL_50Y"
    );
    /** 기간을 밝히지 않은 `공공임대`는 분양전환·50년 공공임대 단지 모두에 대어 본다. */
    private static final String ANY_PUBLIC_RENTAL = "ANY_PUBLIC_RENTAL";
    private static final List<String> NAME_DECORATIONS = List.of(
            "통합공공임대주택",
            "국민임대주택",
            "영구임대주택",
            "공공임대주택",
            "행복주택",
            "주공아파트",
            "공공임대",
            "국민임대",
            "영구임대",
            "임대주택",
            "휴먼시아",
            "아파트",
            "공임리츠",
            "마을",
            "단지",
            "블록",
            "블럭",
            "지구",
            "리츠",
            "주공",
            "lh"
    );
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    public List<HousingComplex> findMatches(
            List<HousingComplex> complexes,
            LhHousingTypeHouseholdSource source
    ) {
        return findMatches(index(complexes), source);
    }

    Map<MatchKey, List<HousingComplex>> index(List<HousingComplex> complexes) {
        Map<MatchKey, List<HousingComplex>> indexed = new HashMap<>();
        for (HousingComplex complex : complexes) {
            if (!"LH".equals(complex.getProvider())) {
                continue;
            }
            MatchKey key = new MatchKey(complex.getSupplyType(), complex.getTotalHouseholdCount());
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(complex);
            if (PUBLIC_RENTAL_TYPES.contains(complex.getSupplyType())) {
                MatchKey publicRentalKey = new MatchKey(ANY_PUBLIC_RENTAL, complex.getTotalHouseholdCount());
                indexed.computeIfAbsent(publicRentalKey, ignored -> new ArrayList<>()).add(complex);
            }
        }
        return indexed;
    }

    List<HousingComplex> findMatches(
            Map<MatchKey, List<HousingComplex>> indexed,
            LhHousingTypeHouseholdSource source
    ) {
        return bestNameMatches(structuralMatches(indexed, source), source.complexName());
    }

    /** 이름을 비교하기 전, 공급유형·전체 세대수·지역이 같은 LH 단지 후보다. */
    List<HousingComplex> structuralMatches(
            Map<MatchKey, List<HousingComplex>> indexed,
            LhHousingTypeHouseholdSource source
    ) {
        String supplyType = sourceSupplyType(source.supplyTypeName());
        if (supplyType == null) {
            return List.of();
        }
        MatchKey key = new MatchKey(supplyType, source.totalHouseholdCount());
        return indexed.getOrDefault(key, List.of()).stream()
                .filter(complex -> regionMatches(complex, source.areaName()))
                .toList();
    }

    private String sourceSupplyType(String supplyTypeName) {
        String normalized = LhCatalogNames.normalized(supplyTypeName);
        if ("공공임대".equals(normalized)) {
            return ANY_PUBLIC_RENTAL;
        }
        return SUPPLY_TYPES.get(normalized);
    }

    public boolean hasExactIdentity(HousingComplex complex, String name, String region, String supplyType) {
        return "LH".equals(complex.getProvider())
                && LhCatalogNames.normalized(complex.getName()).equals(LhCatalogNames.normalized(name))
                && regionMatches(complex, region)
                && complex.getSupplyType().equals(sourceSupplyType(supplyType));
    }

    private List<HousingComplex> bestNameMatches(
            List<HousingComplex> complexes,
            String sourceName
    ) {
        int bestScore = 0;
        List<HousingComplex> bestMatches = new ArrayList<>();
        for (HousingComplex complex : complexes) {
            int score = nameScore(sourceName, complex.getName());
            if (score == 0 || score < bestScore) {
                continue;
            }
            if (score > bestScore) {
                bestMatches.clear();
                bestScore = score;
            }
            bestMatches.add(complex);
        }
        return bestMatches;
    }

    private int nameScore(String sourceName, String candidateName) {
        String normalizedSource = LhCatalogNames.normalized(sourceName);
        String normalizedCandidate = LhCatalogNames.normalized(candidateName);
        if (normalizedSource.equals(normalizedCandidate)) {
            return 10_000;
        }

        String source = comparableName(normalizedSource);
        String candidate = comparableName(normalizedCandidate);
        if (source.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        if (source.equals(candidate)) {
            return 9_000;
        }
        Set<String> sourceNumbers = numbers(source);
        Set<String> candidateNumbers = numbers(candidate);
        if (hasConflictingNumbers(sourceNumbers, candidateNumbers)) {
            return 0;
        }

        int shorterLength = Math.min(source.length(), candidate.length());
        int longerLength = Math.max(source.length(), candidate.length());
        if (shorterLength >= 3 && (source.contains(candidate) || candidate.contains(source))) {
            return 8_000 + shorterLength * 100 / longerLength;
        }

        int commonLength = longestCommonSubstringLength(source, candidate);
        int diceScore = bigramDiceScore(source, candidate);
        boolean sharesNumber = candidateNumbers.stream().anyMatch(sourceNumbers::contains);
        if (commonLength < 4 && diceScore < 450 && !(commonLength >= 2 && sharesNumber)) {
            return 0;
        }
        int score = 1_000 + commonLength * 100 + diceScore;
        if (sharesNumber) {
            return score + 200;
        }
        return score;
    }

    private String comparableName(String normalizedName) {
        String result = normalizedName.replaceAll("nhf제?[0-9]*호?", "");
        for (String decoration : NAME_DECORATIONS) {
            result = result.replace(decoration, "");
        }
        return result;
    }

    private int longestCommonSubstringLength(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int longest = 0;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                if (left.charAt(leftIndex - 1) != right.charAt(rightIndex - 1)) {
                    continue;
                }
                current[rightIndex] = previous[rightIndex - 1] + 1;
                longest = Math.max(longest, current[rightIndex]);
            }
            previous = current;
        }
        return longest;
    }

    private int bigramDiceScore(String left, String right) {
        if (left.length() < 2 || right.length() < 2) {
            return 0;
        }
        Set<String> leftBigrams = bigrams(left);
        Set<String> rightBigrams = bigrams(right);
        long intersection = leftBigrams.stream().filter(rightBigrams::contains).count();
        return (int) (2_000L * intersection / (leftBigrams.size() + rightBigrams.size()));
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < value.length() - 1; index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private boolean hasConflictingNumbers(Set<String> leftNumbers, Set<String> rightNumbers) {
        return !leftNumbers.isEmpty() && !rightNumbers.isEmpty() && !leftNumbers.equals(rightNumbers);
    }

    private Set<String> numbers(String value) {
        Set<String> result = new HashSet<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            result.add(SupplyNameNormalizer.withoutLeadingZeros(matcher.group()));
        }
        return result;
    }

    private boolean regionMatches(HousingComplex complex, String areaName) {
        String address = LhCatalogNames.region(complex.getAddress().getRoadAddress());
        String sourceRegion = LhCatalogNames.region(areaName);
        return !sourceRegion.isEmpty() && address.startsWith(sourceRegion);
    }

    record MatchKey(String supplyType, int totalHouseholdCount) {
    }
}
