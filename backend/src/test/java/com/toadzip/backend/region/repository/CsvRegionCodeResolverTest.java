package com.toadzip.backend.region.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class CsvRegionCodeResolverTest {

    private static final String HEADER = "regionCode,sido,sigungu,name";
    private static final String ALIAS_HEADER = "legacyRegionCode,currentRegionCode";
    private static final String SOURCE_METADATA =
            "# source=https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardArticle.do"
                    + "?bbsId=BBSMSTR_000000000052&nttId=127039";
    private static final String EFFECTIVE_DATE_METADATA = "# effectiveDate=2026-07-01";

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

    @ParameterizedTest
    @CsvSource({
            "1, 11140",
            "11140, 11140",
            "ab, 11140",
            "11, 1114",
            "11, 111400",
            "11, 11A40",
            "26, 11140"
    })
    void 형식이_잘못되거나_서로_불일치하는_지역코드_pair는_해석하지_않는다(
            String provinceCode,
            String cityCountyDistrictCode
    ) {
        assertTrue(resolver("11140,서울특별시,중구,서울특별시 중구")
                .resolve(provinceCode, cityCountyDistrictCode)
                .isEmpty());
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
    void 직전_행정구역코드는_현재_지역명으로_해석한다() {
        CsvRegionCodeResolver resolver = resolverWithContentsAndAliases(
                HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                ALIAS_HEADER + "\n29110,12210"
        );

        assertEquals("전남광주통합특별시 동구", resolver.resolve("29", "29110").orElseThrow());
        assertEquals("전남광주통합특별시 동구", resolver.resolve("12", "12210").orElseThrow());
    }

    @Test
    void 현재와_과거_시군구코드는_같은_동등코드_집합으로_해석한다() {
        CsvRegionCodeResolver resolver = resolverWithContentsAndAliases(
                HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                ALIAS_HEADER + "\n29110,12210"
        );

        Set<String> expected = Set.of("12210", "29110");
        Set<String> currentCodes = resolver.equivalentCodes("12210").orElseThrow();
        Set<String> legacyCodes = resolver.equivalentCodes("29110").orElseThrow();
        assertEquals(expected, currentCodes);
        assertEquals(expected, legacyCodes);
        assertSame(currentCodes, legacyCodes);
    }

    @Test
    void 동등_지역코드_집합은_불변이다() {
        CsvRegionCodeResolver resolver = resolverWithContentsAndAliases(
                HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                ALIAS_HEADER + "\n29110,12210"
        );

        Set<String> equivalentCodes = resolver.equivalentCodes("12210").orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> equivalentCodes.add("99999"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "1111", "111111", "12A10", "99999"})
    void 형식이_잘못되었거나_미등록된_시군구코드는_동등코드로_해석하지_않는다(
            String regionCode
    ) {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertTrue(resolver.equivalentCodes(regionCode).isEmpty());
    }

    @Test
    void 정본_지역에_존재하는_시도_prefix만_등록된_시도코드다() {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertTrue(resolver.isRegisteredProvinceCode("11"));
        assertFalse(resolver.isRegisteredProvinceCode("99"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "111", "1A", "99"})
    void 형식이_잘못되었거나_미등록된_시도코드는_등록되지_않는다(String provinceCode) {
        CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

        assertFalse(resolver.isRegisteredProvinceCode(provinceCode));
    }

    @Test
    void 별칭의_현재_지역코드가_정본에_없으면_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolverWithContentsAndAliases(
                        HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                        ALIAS_HEADER + "\n29110,12999"
                )
        );

        assertTrue(exception.getMessage().contains("12999"));
        assertTrue(exception.getMessage().contains("currentRegionCode"));
    }

    @Test
    void 중복된_직전_지역코드가_있으면_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolverWithContentsAndAliases(
                        HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                        ALIAS_HEADER + "\n29110,12210\n29110,12210"
                )
        );

        assertTrue(exception.getMessage().contains("duplicate"));
        assertTrue(exception.getMessage().contains("29110"));
    }

    @Test
    void 별칭_지역코드가_다섯자리_숫자가_아니면_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolverWithContentsAndAliases(
                        HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                        ALIAS_HEADER + "\n2911,12210"
                )
        );

        assertTrue(exception.getMessage().contains("legacyRegionCode"));
        assertTrue(exception.getMessage().contains("five digits"));
    }

    @Test
    void 별칭의_직전_지역코드가_현재_정본과_충돌하면_초기화에_실패한다() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolverWithContentsAndAliases(
                        HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
                        ALIAS_HEADER + "\n12210,12210"
                )
        );

        assertTrue(exception.getMessage().contains("12210"));
        assertTrue(exception.getMessage().contains("canonical"));
    }

    @Test
    void 공식_리소스는_출처와_시행일_및_직전_지역코드_별칭을_포함한다() throws IOException {
        Resource resource = new ClassPathResource("region/regions.csv");
        Resource aliasResource = new ClassPathResource("region/region-code-aliases.csv");
        List<String> lines = readLines(resource);
        List<String> aliasLines = readLines(aliasResource);
        List<String> regionRows = dataRows(lines);
        List<String> aliasRows = dataRows(aliasLines);

        assertEquals(HEADER, lines.getFirst());
        assertEquals(ALIAS_HEADER, aliasLines.getFirst());
        assertTrue(lines.contains(SOURCE_METADATA));
        assertTrue(lines.contains(EFFECTIVE_DATE_METADATA));
        assertTrue(aliasLines.contains(SOURCE_METADATA));
        assertTrue(aliasLines.contains(EFFECTIVE_DATE_METADATA));
        assertEquals(269, regionRows.size());
        assertEquals(269, uniqueFirstColumn(regionRows).size());
        assertEquals(27, aliasRows.size());
        assertEquals(27, uniqueFirstColumn(aliasRows).size());
        assertTrue(regionRows.contains("11140,서울특별시,중구,서울특별시 중구"));
        assertTrue(regionRows.contains("36110,세종특별자치시,세종특별자치시,세종특별자치시"));
        assertTrue(aliasRows.contains("29110,12210"));
        assertTrue(aliasRows.contains("46110,12110"));

        CsvRegionCodeResolver resolver = new CsvRegionCodeResolver(resource, aliasResource);
        assertEquals("서울특별시 중구", resolver.resolve("11", "11140").orElseThrow());
        assertEquals("세종특별자치시", resolver.resolve("36", "36110").orElseThrow());
        assertEquals("전남광주통합특별시 동구", resolver.resolve("29", "29110").orElseThrow());
        assertEquals("전남광주통합특별시 목포시", resolver.resolve("46", "46110").orElseThrow());
    }

    private static CsvRegionCodeResolver resolver(String... rows) {
        String contents = HEADER + "\n" + String.join("\n", rows);
        return resolverWithContents(contents);
    }

    private static CsvRegionCodeResolver resolverWithContents(String contents) {
        return resolverWithContentsAndAliases(contents, ALIAS_HEADER);
    }

    private static CsvRegionCodeResolver resolverWithContentsAndAliases(String contents, String aliasContents) {
        Resource resource = new ByteArrayResource(contents.getBytes(StandardCharsets.UTF_8));
        Resource aliasResource = new ByteArrayResource(aliasContents.getBytes(StandardCharsets.UTF_8));
        return new CsvRegionCodeResolver(resource, aliasResource);
    }

    private static List<String> readLines(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static List<String> dataRows(List<String> lines) {
        return lines.stream()
                .skip(1)
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private static Set<String> uniqueFirstColumn(List<String> lines) {
        Set<String> regionCodes = new HashSet<>();
        lines.stream()
                .map(line -> line.split(",", -1)[0])
                .forEach(regionCodes::add);
        return regionCodes;
    }
}
