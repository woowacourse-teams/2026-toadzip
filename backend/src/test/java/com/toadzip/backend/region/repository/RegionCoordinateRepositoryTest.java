package com.toadzip.backend.region.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.housing.repository.CsvMapClusteringRegionPointPolicyRepository;
import com.toadzip.backend.housing.repository.CsvMapClusteringRegionPolicyRepository;
import com.toadzip.backend.housing.repository.CsvMapClusteringZoomPolicyRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RegionCoordinateRepositoryTest {

    private RegionCoordinateRepository repository;

    @BeforeEach
    void setUp() {
        var zoomRepository = new CsvMapClusteringZoomPolicyRepository(
                classpath("map-clustering/stage-transitions.csv")
        );
        var regionRepository = new CsvMapClusteringRegionPolicyRepository(
                classpath("map-clustering/groups.csv"),
                classpath("map-clustering/memberships.csv"),
                classpath("region/regions.csv"),
                zoomRepository
        );
        repository = new RegionCoordinateRepository(new CsvMapClusteringRegionPointPolicyRepository(
                classpath("map-clustering/representative-points.csv"), regionRepository
        ));
    }

    @Test
    void 수원시_코드와_정확히_일치하는_기초지역_대표좌표를_반환한다() {
        MapCoordinate coordinate = repository.findByRegionCode("41110").orElseThrow();

        assertEquals(new BigDecimal("37.27532584"), coordinate.latitude());
        assertEquals(new BigDecimal("127.01641895"), coordinate.longitude());
    }

    @Test
    void 경기도_코드와_정확히_일치하는_광역지역_대표좌표를_반환한다() {
        MapCoordinate coordinate = repository.findByRegionCode("41").orElseThrow();

        assertEquals(new BigDecimal("37.44627299"), coordinate.latitude());
        assertEquals(new BigDecimal("127.02967758"), coordinate.longitude());
    }

    @Test
    void 장안구에_자체_대표좌표가_없으면_부모인_수원시_좌표를_대신_사용하지_않는다() {
        assertTrue(repository.findByRegionCode("41111").isEmpty());
    }

    @Test
    void 존재하지_않거나_유효하지_않은_코드는_좌표를_반환하지_않는다() {
        assertTrue(repository.findByRegionCode("99999").isEmpty());
        assertTrue(repository.findByRegionCode("4111000000").isEmpty());
        assertTrue(repository.findByRegionCode("수원시").isEmpty());
        assertTrue(repository.findByRegionCode(null).isEmpty());
    }

    private static ClassPathResource classpath(String path) {
        return new ClassPathResource(path);
    }
}
