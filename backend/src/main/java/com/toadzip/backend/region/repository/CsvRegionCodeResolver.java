package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvRegionCodeResolver implements RegionCodeResolver, RegionSearchRepository {

    private static final String EXPECTED_HEADER = "regionCode,sido,sigungu,name";
    private static final String EXPECTED_ALIAS_HEADER = "legacyRegionCode,currentRegionCode";
    private static final int COLUMN_COUNT = 4;
    private static final int ALIAS_COLUMN_COUNT = 2;
    private static final int REGION_CODE_INDEX = 0;
    private static final int PROVINCE_NAME_INDEX = 1;
    private static final int DISTRICT_NAME_INDEX = 2;
    private static final int DISPLAY_NAME_INDEX = 3;
    private static final int LEGACY_REGION_CODE_INDEX = 0;
    private static final int CURRENT_REGION_CODE_INDEX = 1;

    private final Map<String, RegionSearchResult> canonicalRegions;
    private final Map<String, String> regionCodeAliases;
    private final List<RegionSearchResult> searchResults;
    private final Map<String, Set<String>> provinceCodeEquivalences;

    @Autowired
    CsvRegionCodeResolver(
            @Value("classpath:region/regions.csv") Resource regionResource,
            @Value("classpath:region/region-code-aliases.csv") Resource aliasResource
    ) {
        canonicalRegions = loadCanonicalRegions(regionResource);
        regionCodeAliases = loadRegionCodeAliases(aliasResource, canonicalRegions);
        searchResults = createSearchResults(canonicalRegions);
        provinceCodeEquivalences = createProvinceCodeEquivalences(canonicalRegions, regionCodeAliases);
    }

    @Override
    public Optional<String> resolve(String provinceCode, String cityCountyDistrictCode) {
        if (provinceCode == null || cityCountyDistrictCode == null) {
            return Optional.empty();
        }
        if (!provinceCode.matches("[0-9]{2}") || !cityCountyDistrictCode.matches("[0-9]{5}")) {
            return Optional.empty();
        }
        if (!cityCountyDistrictCode.substring(0, 2).equals(provinceCode)) {
            return Optional.empty();
        }
        String currentRegionCode = regionCodeAliases.getOrDefault(
                cityCountyDistrictCode,
                cityCountyDistrictCode
        );
        return Optional.ofNullable(canonicalRegions.get(currentRegionCode))
                .map(RegionSearchResult::displayName);
    }

    @Override
    public List<RegionSearchResult> findByKeyword(String normalizedKeyword) {
        return searchResults.stream()
                .filter(region -> matches(normalizedKeyword, region))
                .toList();
    }

    @Override
    public Optional<Set<String>> equivalentProvinceCodes(String provinceCode) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(provinceCodeEquivalences.get(provinceCode));
    }

    private static boolean matches(String keyword, RegionSearchResult region) {
        if (region.districtName() == null) {
            return region.provinceName().contains(keyword);
        }
        return region.provinceName().contains(keyword)
                || region.districtName().contains(keyword)
                || region.displayName().contains(keyword);
    }

    private static Map<String, RegionSearchResult> loadCanonicalRegions(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            validateHeader(reader.readLine(), resource);
            return readRows(reader, resource);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read region CSV: " + resource.getDescription(), exception);
        }
    }

    private static void validateHeader(String header, Resource resource) {
        if (EXPECTED_HEADER.equals(header)) {
            return;
        }
        throw new IllegalStateException(
                "Invalid region CSV header in " + resource.getDescription() + ": expected " + EXPECTED_HEADER
        );
    }

    private static Map<String, RegionSearchResult> readRows(BufferedReader reader, Resource resource)
            throws IOException {
        Map<String, RegionSearchResult> canonicalRegions = new HashMap<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("#")) {
                continue;
            }
            addRow(canonicalRegions, line, lineNumber, resource);
        }
        return Map.copyOf(canonicalRegions);
    }

    private static void addRow(
            Map<String, RegionSearchResult> canonicalRegions,
            String line,
            int lineNumber,
            Resource resource
    ) {
        String[] cells = line.split(",", -1);
        validateColumnCount(cells, lineNumber, resource);
        validateRequiredCells(cells, lineNumber, resource);
        String regionCode = cells[REGION_CODE_INDEX];
        validateRegionCode(regionCode, lineNumber, resource);
        RegionSearchResult previousRegion = canonicalRegions.putIfAbsent(
                regionCode,
                new RegionSearchResult(
                        regionCode,
                        cells[PROVINCE_NAME_INDEX],
                        cells[DISTRICT_NAME_INDEX],
                        cells[DISPLAY_NAME_INDEX]
                )
        );
        if (previousRegion != null) {
            throw invalidRow(resource, lineNumber, "duplicate regionCode '" + regionCode + "'");
        }
    }

    private static void validateColumnCount(String[] cells, int lineNumber, Resource resource) {
        if (cells.length == COLUMN_COUNT) {
            return;
        }
        throw invalidRow(resource, lineNumber, "expected 4 columns but found " + cells.length);
    }

    private static void validateRequiredCells(String[] cells, int lineNumber, Resource resource) {
        validateRequiredCell(cells[0], "regionCode", lineNumber, resource);
        validateRequiredCell(cells[1], "sido", lineNumber, resource);
        validateRequiredCell(cells[2], "sigungu", lineNumber, resource);
        validateRequiredCell(cells[3], "name", lineNumber, resource);
    }

    private static void validateRequiredCell(String cell, String columnName, int lineNumber, Resource resource) {
        if (!cell.isBlank()) {
            return;
        }
        throw invalidRow(resource, lineNumber, "blank required column '" + columnName + "'");
    }

    private static void validateRegionCode(String regionCode, int lineNumber, Resource resource) {
        if (regionCode.matches("\\d{5}")) {
            return;
        }
        throw invalidRow(resource, lineNumber, "regionCode must be exactly five digits");
    }

    private static IllegalStateException invalidRow(Resource resource, int lineNumber, String reason) {
        return new IllegalStateException(
                "Invalid region CSV " + resource.getDescription() + " at line " + lineNumber + ": " + reason
        );
    }

    private static Map<String, String> loadRegionCodeAliases(
            Resource resource,
            Map<String, RegionSearchResult> canonicalRegions
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            validateAliasHeader(reader.readLine(), resource);
            return readAliasRows(reader, resource, canonicalRegions);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read region alias CSV: " + resource.getDescription(), exception);
        }
    }

    private static void validateAliasHeader(String header, Resource resource) {
        if (EXPECTED_ALIAS_HEADER.equals(header)) {
            return;
        }
        throw new IllegalStateException(
                "Invalid region alias CSV header in " + resource.getDescription()
                        + ": expected " + EXPECTED_ALIAS_HEADER
        );
    }

    private static Map<String, String> readAliasRows(
            BufferedReader reader,
            Resource resource,
            Map<String, RegionSearchResult> canonicalRegions
    ) throws IOException {
        Map<String, String> regionCodeAliases = new HashMap<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("#")) {
                continue;
            }
            addAliasRow(regionCodeAliases, canonicalRegions, line, lineNumber, resource);
        }
        return Map.copyOf(regionCodeAliases);
    }

    private static void addAliasRow(
            Map<String, String> regionCodeAliases,
            Map<String, RegionSearchResult> canonicalRegions,
            String line,
            int lineNumber,
            Resource resource
    ) {
        String[] cells = line.split(",", -1);
        validateAliasColumnCount(cells, lineNumber, resource);
        String legacyRegionCode = cells[LEGACY_REGION_CODE_INDEX];
        String currentRegionCode = cells[CURRENT_REGION_CODE_INDEX];
        validateAliasRegionCode(legacyRegionCode, "legacyRegionCode", lineNumber, resource);
        validateAliasRegionCode(currentRegionCode, "currentRegionCode", lineNumber, resource);
        validateAliasRelationship(legacyRegionCode, currentRegionCode, canonicalRegions, lineNumber, resource);
        String previousCode = regionCodeAliases.putIfAbsent(legacyRegionCode, currentRegionCode);
        if (previousCode != null) {
            throw invalidAliasRow(resource, lineNumber, "duplicate legacyRegionCode '" + legacyRegionCode + "'");
        }
    }

    private static void validateAliasColumnCount(String[] cells, int lineNumber, Resource resource) {
        if (cells.length == ALIAS_COLUMN_COUNT) {
            return;
        }
        throw invalidAliasRow(resource, lineNumber, "expected 2 columns but found " + cells.length);
    }

    private static void validateAliasRegionCode(
            String regionCode,
            String columnName,
            int lineNumber,
            Resource resource
    ) {
        if (regionCode.matches("\\d{5}")) {
            return;
        }
        throw invalidAliasRow(resource, lineNumber, columnName + " must be exactly five digits");
    }

    private static void validateAliasRelationship(
            String legacyRegionCode,
            String currentRegionCode,
            Map<String, RegionSearchResult> canonicalRegions,
            int lineNumber,
            Resource resource
    ) {
        if (canonicalRegions.containsKey(legacyRegionCode)) {
            throw invalidAliasRow(
                    resource,
                    lineNumber,
                    "legacyRegionCode '" + legacyRegionCode + "' conflicts with canonical regionCode"
            );
        }
        if (!canonicalRegions.containsKey(currentRegionCode)) {
            throw invalidAliasRow(
                    resource,
                    lineNumber,
                    "currentRegionCode '" + currentRegionCode + "' is not a canonical regionCode"
            );
        }
    }

    private static IllegalStateException invalidAliasRow(Resource resource, int lineNumber, String reason) {
        return new IllegalStateException(
                "Invalid region alias CSV " + resource.getDescription() + " at line " + lineNumber + ": " + reason
        );
    }

    private static List<RegionSearchResult> createSearchResults(
            Map<String, RegionSearchResult> canonicalRegions
    ) {
        Map<String, RegionSearchResult> provinceAggregates = new HashMap<>();
        canonicalRegions.values().forEach(region -> provinceAggregates.putIfAbsent(
                provinceCode(region.regionCode()),
                new RegionSearchResult(
                        provinceCode(region.regionCode()),
                        region.provinceName(),
                        null,
                        region.provinceName() + " 전체"
                )
        ));
        List<RegionSearchResult> results = new ArrayList<>(provinceAggregates.values());
        results.addAll(canonicalRegions.values());
        results.sort(Comparator.comparing(
                (RegionSearchResult region) -> provinceCode(region.regionCode())
        ).thenComparing(CsvRegionCodeResolver::aggregateOrder)
                .thenComparing(RegionSearchResult::regionCode));
        return List.copyOf(results);
    }

    private static String provinceCode(String regionCode) {
        return regionCode.substring(0, 2);
    }

    private static int aggregateOrder(RegionSearchResult region) {
        if (region.regionCode().length() == 2) {
            return 0;
        }
        return 1;
    }

    private static Map<String, Set<String>> createProvinceCodeEquivalences(
            Map<String, RegionSearchResult> canonicalRegions,
            Map<String, String> regionCodeAliases
    ) {
        Set<String> canonicalProvinceCodes = canonicalProvinceCodes(canonicalRegions);
        Map<String, String> legacyProvinceCodeTargets = legacyProvinceCodeTargets(
                regionCodeAliases,
                canonicalProvinceCodes
        );
        Map<String, Set<String>> equivalenceGroups = createEquivalenceGroups(canonicalProvinceCodes);
        legacyProvinceCodeTargets.forEach((legacyProvinceCode, currentProvinceCode) -> equivalenceGroups
                .get(currentProvinceCode)
                .add(legacyProvinceCode));
        return immutableEquivalenceLookup(equivalenceGroups);
    }

    private static Set<String> canonicalProvinceCodes(Map<String, RegionSearchResult> canonicalRegions) {
        Set<String> provinceCodes = new HashSet<>();
        canonicalRegions.keySet().forEach(regionCode -> provinceCodes.add(provinceCode(regionCode)));
        return Set.copyOf(provinceCodes);
    }

    private static Map<String, String> legacyProvinceCodeTargets(
            Map<String, String> regionCodeAliases,
            Set<String> canonicalProvinceCodes
    ) {
        Map<String, String> targets = new HashMap<>();
        regionCodeAliases.forEach((legacyRegionCode, currentRegionCode) -> addLegacyProvinceCodeTarget(
                targets,
                canonicalProvinceCodes,
                provinceCode(legacyRegionCode),
                provinceCode(currentRegionCode)
        ));
        return Map.copyOf(targets);
    }

    private static void addLegacyProvinceCodeTarget(
            Map<String, String> targets,
            Set<String> canonicalProvinceCodes,
            String legacyProvinceCode,
            String currentProvinceCode
    ) {
        if (canonicalProvinceCodes.contains(legacyProvinceCode)) {
            return;
        }
        String previousCurrentProvinceCode = targets.putIfAbsent(legacyProvinceCode, currentProvinceCode);
        if (previousCurrentProvinceCode == null || previousCurrentProvinceCode.equals(currentProvinceCode)) {
            return;
        }
        throw new IllegalStateException(
                "Legacy province code '" + legacyProvinceCode + "' maps to multiple current province codes"
        );
    }

    private static Map<String, Set<String>> createEquivalenceGroups(Set<String> canonicalProvinceCodes) {
        Map<String, Set<String>> groups = new HashMap<>();
        canonicalProvinceCodes.forEach(provinceCode -> groups.put(provinceCode, new HashSet<>(Set.of(provinceCode))));
        return groups;
    }

    private static Map<String, Set<String>> immutableEquivalenceLookup(
            Map<String, Set<String>> equivalenceGroups
    ) {
        Map<String, Set<String>> lookup = new HashMap<>();
        equivalenceGroups.values().forEach(group -> {
            Set<String> immutableGroup = Set.copyOf(group);
            immutableGroup.forEach(provinceCode -> lookup.put(provinceCode, immutableGroup));
        });
        return Map.copyOf(lookup);
    }
}
