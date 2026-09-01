package com.toadzip.backend.ingest.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import java.util.ArrayList;
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
            Map.entry("5년임대", "PUBLIC_RENTAL_5Y"),
            Map.entry("공공임대5년", "PUBLIC_RENTAL_5Y"),
            Map.entry("10년임대", "PUBLIC_RENTAL_10Y"),
            Map.entry("공공임대10년", "PUBLIC_RENTAL_10Y"),
            Map.entry("50년임대", "PUBLIC_RENTAL_50Y"),
            Map.entry("공공임대50년", "PUBLIC_RENTAL_50Y"),
            Map.entry("장기전세", "LONG_TERM_JEONSE"),
            Map.entry("통합공공임대", "INTEGRATED_PUBLIC_RENTAL"),
            Map.entry("재개발임대", "REDEVELOPMENT_RENTAL")
    );
    private static final Set<String> PUBLIC_RENTAL_TYPES = Set.of(
            "PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_10Y", "PUBLIC_RENTAL_50Y"
    );
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
        List<HousingComplex> structuralMatches = complexes.stream()
                .filter(complex -> "LH".equals(complex.getProvider()))
                .filter(complex -> regionMatches(complex, source.areaName()))
                .filter(complex -> supplyTypeMatches(complex.getSupplyType(), source.supplyTypeName()))
                .filter(complex -> complex.getTotalHouseholdCount() == source.totalHouseholdCount())
                .toList();
        return bestNameMatches(structuralMatches, source.complexName());
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
        String normalizedSource = normalized(sourceName);
        String normalizedCandidate = normalized(candidateName);
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

        int shorterLength = Math.min(source.length(), candidate.length());
        int longerLength = Math.max(source.length(), candidate.length());
        if (shorterLength >= 3 && (source.contains(candidate) || candidate.contains(source))) {
            return 8_000 + shorterLength * 100 / longerLength;
        }

        int commonLength = longestCommonSubstringLength(source, candidate);
        int diceScore = bigramDiceScore(source, candidate);
        boolean sharesNumber = sharesNumber(source, candidate);
        if (commonLength < 4 && diceScore < 450 && !(commonLength >= 2 && sharesNumber)) {
            return 0;
        }
        return 1_000 + commonLength * 100 + diceScore + (sharesNumber ? 200 : 0);
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

    private boolean sharesNumber(String left, String right) {
        Set<String> leftNumbers = new HashSet<>();
        Matcher leftMatcher = NUMBER.matcher(left);
        while (leftMatcher.find()) {
            leftNumbers.add(leftMatcher.group());
        }
        Matcher rightMatcher = NUMBER.matcher(right);
        while (rightMatcher.find()) {
            if (leftNumbers.contains(rightMatcher.group())) {
                return true;
            }
        }
        return false;
    }

    private boolean regionMatches(HousingComplex complex, String areaName) {
        String address = canonicalRegion(complex.getAddress().getRoadAddress());
        String sourceRegion = canonicalRegion(areaName);
        return !sourceRegion.isEmpty() && address.startsWith(sourceRegion);
    }

    private boolean supplyTypeMatches(String myHomeSupplyType, String lhSupplyType) {
        String normalized = normalized(lhSupplyType);
        if ("공공임대".equals(normalized)) {
            return PUBLIC_RENTAL_TYPES.contains(myHomeSupplyType);
        }
        return myHomeSupplyType.equals(SUPPLY_TYPES.get(normalized));
    }

    private String canonicalRegion(String value) {
        return normalized(value)
                .replace("서울특별시", "서울")
                .replace("부산광역시", "부산")
                .replace("대구광역시", "대구")
                .replace("인천광역시", "인천")
                .replace("광주광역시", "광주")
                .replace("대전광역시", "대전")
                .replace("울산광역시", "울산")
                .replace("세종특별자치시", "세종")
                .replace("강원특별자치도", "강원")
                .replace("강원도", "강원")
                .replace("전북특별자치도", "전북")
                .replace("전라북도", "전북")
                .replace("제주특별자치도", "제주")
                .replace("경기도", "경기")
                .replace("충청북도", "충북")
                .replace("충청남도", "충남")
                .replace("전라남도", "전남")
                .replace("경상북도", "경북")
                .replace("경상남도", "경남");
    }

    private String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-·,()]", "").strip().toLowerCase();
    }
}
