package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvRegionCodeResolver implements RegionCodeResolver {

    private static final String EXPECTED_HEADER = "regionCode,sido,sigungu,name";
    private static final int COLUMN_COUNT = 4;
    private static final int REGION_CODE_INDEX = 0;
    private static final int NAME_INDEX = 3;

    private final Map<String, String> regionNames;

    @Autowired
    CsvRegionCodeResolver(@Value("classpath:region/regions.csv") Resource resource) {
        regionNames = loadRegionNames(resource);
    }

    @Override
    public Optional<String> resolve(String provinceCode, String cityCountyDistrictCode) {
        if (provinceCode == null || cityCountyDistrictCode == null) {
            return Optional.empty();
        }
        if (!provinceCode.matches("\\d{2}")) {
            return Optional.empty();
        }
        if (!cityCountyDistrictCode.startsWith(provinceCode)) {
            return Optional.empty();
        }
        return Optional.ofNullable(regionNames.get(cityCountyDistrictCode));
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
}
