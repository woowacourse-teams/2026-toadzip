package com.toadzip.backend.region.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CsvRegionCodeResolverTest {

    private final CsvRegionCodeResolver resolver = new CsvRegionCodeResolver(
            new ClassPathResource("region/regions.csv")
    );

    @Test
    void 공식_시도와_시군구_코드로_지역명을_해석한다() {
        assertEquals(Optional.of("서울특별시 중구"), resolver.resolve("11", "11140"));
    }

    @Test
    void 알_수_없는_지역코드는_해석하지_않는다() {
        assertEquals(Optional.empty(), resolver.resolve("99", "99999"));
    }
}
