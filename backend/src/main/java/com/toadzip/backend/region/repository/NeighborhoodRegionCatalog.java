package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

final class NeighborhoodRegionCatalog {

    private NeighborhoodRegionCatalog() {
    }

    static Map<String, Set<String>> equivalentCodes(Map<String, RegionSearchResult> neighborhoods) {
        if (neighborhoods.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> codes = new HashMap<>();
        neighborhoods.keySet().forEach(code -> codes.put(code, new HashSet<>(Set.of(code))));
        var resource = new ClassPathResource("region/neighborhood-code-aliases.csv");
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            if (!"legacyRegionCode,currentRegionCode".equals(reader.readLine())) {
                throw new IllegalStateException("Invalid neighborhood aliases header");
            }
            for (String line : reader.lines().toList()) {
                String[] cells = line.split(",", -1);
                if (cells.length != 2 || !cells[0].matches("[0-9]{8}00") || !codes.containsKey(cells[1])) {
                    throw new IllegalStateException("Invalid neighborhood alias: " + line);
                }
                codes.get(cells[1]).add(cells[0]);
            }
            Map<String, Set<String>> result = new HashMap<>();
            codes.values().forEach(group -> group.forEach(code -> result.put(code, Set.copyOf(group))));
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read neighborhood aliases", exception);
        }
    }

    static Map<String, RegionSearchResult> load(Resource resource, Map<String, RegionSearchResult> districts) {
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            if (!"regionCode,sido,sigungu,name".equals(reader.readLine())) {
                throw new IllegalStateException("Invalid neighborhood catalog header");
            }
            Map<String, RegionSearchResult> regions = new HashMap<>();
            for (String line : reader.lines().toList()) {
                add(regions, districts, line);
            }
            return Map.copyOf(regions);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read neighborhood catalog", exception);
        }
    }

    private static void add(Map<String, RegionSearchResult> regions,
            Map<String, RegionSearchResult> districts, String line) {
        String[] cells = line.split(",", -1);
        if (cells.length != 4 || !cells[0].matches("[0-9]{8}00") || cells[0].endsWith("00000")) {
            throw new IllegalStateException("Invalid neighborhood catalog row: " + line);
        }
        RegionSearchResult district = districts.get(cells[0].substring(0, 5));
        if (district == null || !district.provinceName().equals(cells[1])
                || !district.districtName().equals(cells[2]) || cells[3].isBlank()) {
            throw new IllegalStateException("Invalid neighborhood parent: " + line);
        }
        var region = new RegionSearchResult(cells[0], cells[1], cells[2], cells[3]);
        if (regions.putIfAbsent(cells[0], region) != null) {
            throw new IllegalStateException("Duplicate neighborhood: " + cells[0]);
        }
    }
}
