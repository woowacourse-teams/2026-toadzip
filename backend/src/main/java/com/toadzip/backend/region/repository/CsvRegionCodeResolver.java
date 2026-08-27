package com.toadzip.backend.region.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvRegionCodeResolver implements RegionCodeResolver {

    private static final String EXPECTED_HEADER = "regionCode,sido,sigungu,name";

    private final Map<String, String> regionNames;

    CsvRegionCodeResolver(@Value("classpath:region/regions.csv") Resource resource) {
        regionNames = load(resource);
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
        return Optional.ofNullable(regionNames.get(cityCountyDistrictCode));
    }

    private Map<String, String> load(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            requireHeader(reader.readLine());
            return readRows(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("지역코드 CSV를 읽을 수 없다.", exception);
        }
    }

    private void requireHeader(String header) {
        if (!EXPECTED_HEADER.equals(header)) {
            throw new IllegalStateException("지역코드 CSV 헤더가 올바르지 않다.");
        }
    }

    private Map<String, String> readRows(BufferedReader reader) throws IOException {
        Map<String, String> names = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#")) {
                continue;
            }
            addRow(names, line);
        }
        return Map.copyOf(names);
    }

    private void addRow(Map<String, String> names, String line) {
        String[] cells = line.split(",", -1);
        if (cells.length != 4) {
            throw new IllegalStateException("지역코드 CSV 행이 올바르지 않다.");
        }
        String previous = names.putIfAbsent(cells[0], cells[3]);
        if (previous != null) {
            throw new IllegalStateException("지역코드 CSV에 중복 코드가 있다.");
        }
    }
}
