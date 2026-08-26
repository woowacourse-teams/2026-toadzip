package com.toadzip.backend.region.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class CsvRegionCodeResolverTest {

    private static final String HEADER = "regionCode,sido,sigungu,name";

    @Test
    void 시도코드와_시군구코드가_일치하면_공식_지역명을_반환한다() {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertEquals("서울특별시 중구", resolver.resolve("11", "11140").orElseThrow());
    }

    @Test
    void 알수없는_시군구코드는_빈_결과를_반환한다() {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertTrue(resolver.resolve("11", "99999").isEmpty());
    }

    @Test
    void 시도코드와_시군구코드의_앞자리가_다르면_빈_결과를_반환한다() {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertTrue(resolver.resolve("26", "11140").isEmpty());
    }

    @Test
    void 시도코드가_두자리_숫자가_아니면_빈_결과를_반환한다() {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertTrue(resolver.resolve("", "11140").isEmpty());
        assertTrue(resolver.resolve("1", "11140").isEmpty());
        assertTrue(resolver.resolve("111", "11140").isEmpty());
    }

    @Test
    void 헤더가_정확하지_않으면_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolverWithContents(
                        "code,sido,sigungu,name\n11140,서울특별시,중구,서울특별시 중구"
                )
        );

        assertTrue(exception.getMessage().contains("header"));
    }

    @Test
    void 지역코드가_다섯자리_숫자가_아니면_해당_줄을_알리며_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolver("1114,서울특별시,중구,서울특별시 중구")
        );

        assertTrue(exception.getMessage().contains("line 2"));
        assertTrue(exception.getMessage().contains("regionCode"));
    }

    @Test
    void 중복된_지역코드가_있으면_해당_코드를_알리며_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolver(
                        "11140,서울특별시,중구,서울특별시 중구",
                        "11140,서울특별시,다른구,서울특별시 다른구"
                )
        );

        assertTrue(exception.getMessage().contains("duplicate"));
        assertTrue(exception.getMessage().contains("11140"));
    }

    @Test
    void 필수_셀이_비어있으면_열과_줄을_알리며_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolver("11140,서울특별시,,서울특별시 중구")
        );

        assertTrue(exception.getMessage().contains("line 2"));
        assertTrue(exception.getMessage().contains("sigungu"));
    }

    @Test
    void 데이터_열이_네개가_아니면_해당_줄을_알리며_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolver("11140,서울특별시,중구")
        );

        assertTrue(exception.getMessage().contains("line 2"));
        assertTrue(exception.getMessage().contains("4 columns"));
    }

    @Test
    void 공식_리소스는_269개_고유_지역과_주요_지역명을_포함한다() throws IOException {
        Resource resource = new ClassPathResource("region/regions.csv");
        List<String> lines = readLines(resource);

        assertEquals(270, lines.size());
        assertEquals(HEADER, lines.getFirst());
        assertEquals(269, uniqueRegionCodes(lines).size());
        assertTrue(lines.contains("11140,서울특별시,중구,서울특별시 중구"));
        assertTrue(lines.contains("36110,세종특별자치시,세종특별자치시,세종특별자치시"));

        CsvRegionCodeResolver resolver = new CsvRegionCodeResolver(resource);
        assertEquals("서울특별시 중구", resolver.resolve("11", "11140").orElseThrow());
        assertEquals("세종특별자치시", resolver.resolve("36", "36110").orElseThrow());
    }

    private static CsvRegionCodeResolver resolver(String... rows) {
        String contents = HEADER + "\n" + String.join("\n", rows);
        return resolverWithContents(contents);
    }

    private static CsvRegionCodeResolver resolverWithContents(String contents) {
        Resource resource = new ByteArrayResource(contents.getBytes(StandardCharsets.UTF_8));
        return new CsvRegionCodeResolver(resource);
    }

    private static List<String> readLines(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static Set<String> uniqueRegionCodes(List<String> lines) {
        Set<String> regionCodes = new HashSet<>();
        lines.stream()
                .skip(1)
                .map(line -> line.split(",", -1)[0])
                .forEach(regionCodes::add);
        return regionCodes;
    }
}
