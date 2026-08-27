package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvRegionCodeResolver implements RegionCodeResolver {

    private static final String EXPECTED_HEADER = "regionCode,sido,sigungu,name";
    private static final String EXPECTED_ALIAS_HEADER = "legacyRegionCode,currentRegionCode";
    private static final int COLUMN_COUNT = 4;
    private static final int ALIAS_COLUMN_COUNT = 2;
    private static final int REGION_CODE_INDEX = 0;
    private static final int NAME_INDEX = 3;
    private static final int LEGACY_REGION_CODE_INDEX = 0;
    private static final int CURRENT_REGION_CODE_INDEX = 1;

    private final Map<String, String> regionNames;
    private final Map<String, String> regionCodeAliases;
    private final Map<String, Set<String>> equivalentRegionCodes;
    private final Set<String> registeredProvinceCodes;

    @Autowired
    CsvRegionCodeResolver(
            @Value("classpath:region/regions.csv") Resource regionResource,
            @Value("classpath:region/region-code-aliases.csv") Resource aliasResource
    ) {
        regionNames = loadRegionNames(regionResource);
        regionCodeAliases = loadRegionCodeAliases(aliasResource, regionNames);
        equivalentRegionCodes = buildEquivalentRegionCodes(regionNames.keySet(), regionCodeAliases);
        registeredProvinceCodes = buildRegisteredProvinceCodes(regionNames.keySet());
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
        return Optional.ofNullable(regionNames.get(currentRegionCode));
    }

    @Override
    public Optional<Set<String>> equivalentCodes(String regionCode) {
        if (regionCode == null || !regionCode.matches("[0-9]{5}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(equivalentRegionCodes.get(regionCode));
    }

    @Override
    public boolean isRegisteredProvinceCode(String provinceCode) {
        return provinceCode != null
                && provinceCode.matches("[0-9]{2}")
                && registeredProvinceCodes.contains(provinceCode);
    }

    private static Map<String, Set<String>> buildEquivalentRegionCodes(
            Set<String> regionCodes,
            Map<String, String> regionCodeAliases
    ) {
        Map<String, Set<String>> codesByCurrentRegionCode = new HashMap<>();
        regionCodes.forEach(regionCode -> codesByCurrentRegionCode.put(regionCode, new HashSet<>(Set.of(regionCode))));
        regionCodeAliases.forEach((legacyRegionCode, currentRegionCode) ->
                codesByCurrentRegionCode.get(currentRegionCode).add(legacyRegionCode));

        Map<String, Set<String>> equivalentCodes = new HashMap<>();
        codesByCurrentRegionCode.values().forEach(codes -> {
            Set<String> immutableCodes = Set.copyOf(codes);
            codes.forEach(regionCode -> equivalentCodes.put(regionCode, immutableCodes));
        });
        return Map.copyOf(equivalentCodes);
    }

    private static Set<String> buildRegisteredProvinceCodes(Set<String> regionCodes) {
        return regionCodes.stream()
                .map(regionCode -> regionCode.substring(0, 2))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> loadRegionNames(Resource resource) {
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

    private static Map<String, String> readRows(BufferedReader reader, Resource resource) throws IOException {
        Map<String, String> regionNames = new HashMap<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("#")) {
                continue;
            }
            addRow(regionNames, line, lineNumber, resource);
        }
        return Map.copyOf(regionNames);
    }

    private static void addRow(
            Map<String, String> regionNames,
            String line,
            int lineNumber,
            Resource resource
    ) {
        String[] cells = line.split(",", -1);
        validateColumnCount(cells, lineNumber, resource);
        validateRequiredCells(cells, lineNumber, resource);
        String regionCode = cells[REGION_CODE_INDEX];
        validateRegionCode(regionCode, lineNumber, resource);
        String previousName = regionNames.putIfAbsent(regionCode, cells[NAME_INDEX]);
        if (previousName != null) {
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
            Map<String, String> regionNames
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            validateAliasHeader(reader.readLine(), resource);
            return readAliasRows(reader, resource, regionNames);
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
            Map<String, String> regionNames
    ) throws IOException {
        Map<String, String> regionCodeAliases = new HashMap<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("#")) {
                continue;
            }
            addAliasRow(regionCodeAliases, regionNames, line, lineNumber, resource);
        }
        return Map.copyOf(regionCodeAliases);
    }

    private static void addAliasRow(
            Map<String, String> regionCodeAliases,
            Map<String, String> regionNames,
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
        validateAliasRelationship(legacyRegionCode, currentRegionCode, regionNames, lineNumber, resource);
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
            Map<String, String> regionNames,
            int lineNumber,
            Resource resource
    ) {
        if (regionNames.containsKey(legacyRegionCode)) {
            throw invalidAliasRow(
                    resource,
                    lineNumber,
                    "legacyRegionCode '" + legacyRegionCode + "' conflicts with canonical regionCode"
            );
        }
        if (!regionNames.containsKey(currentRegionCode)) {
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
}
