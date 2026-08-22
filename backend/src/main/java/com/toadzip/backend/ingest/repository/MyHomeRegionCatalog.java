package com.toadzip.backend.ingest.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import com.toadzip.backend.ingest.dto.MyHomeRegion;

@Repository
public class MyHomeRegionCatalog {

    private static final String RESOURCE_NAME = "myhome-region-codes.csv";

    private final List<MyHomeRegion> regions;

    public MyHomeRegionCatalog() {
        regions = loadRegions();
    }

    public List<MyHomeRegion> findAll() {
        return regions;
    }

    public MyHomeRegion find(String provinceCode, String districtCode) {
        return regions.stream()
                .filter(region -> region.provinceCode().equals(provinceCode))
                .filter(region -> region.districtCode().equals(districtCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("마이홈 지역 코드가 존재하지 않습니다."));
    }

    private List<MyHomeRegion> loadRegions() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_NAME);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
        )) {
            reader.readLine();
            return reader.lines().map(this::parse).toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("마이홈 지역 코드 파일을 읽을 수 없습니다.", exception);
        }
    }

    private MyHomeRegion parse(String line) {
        String[] values = line.split(",", -1);
        if (values.length != 4) {
            throw new IllegalStateException("마이홈 지역 코드 형식이 올바르지 않습니다.");
        }
        return new MyHomeRegion(values[0], values[1], values[2], values[3]);
    }
}
