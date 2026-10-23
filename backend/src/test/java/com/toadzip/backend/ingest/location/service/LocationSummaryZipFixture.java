package com.toadzip.backend.ingest.location.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LocationSummaryZipFixture {

    private static final Charset MS949 = Charset.forName("MS949");
    private static final Map<String, Province> ENTRIES = entries();

    private LocationSummaryZipFixture() {
    }

    static ByteArrayInputStream partialZip() throws IOException {
        return zip(Map.of("entrc_seoul.txt", new Province("11", "서울특별시")));
    }

    static ByteArrayInputStream nationwideZip() throws IOException {
        return zip(ENTRIES);
    }

    static ByteArrayInputStream nationwideZipWithoutTargetAddress() throws IOException {
        return zip(ENTRIES, Set.of(), "다른로");
    }

    static ByteArrayInputStream nationwideZipWithEmptyRegions() throws IOException {
        Set<String> emptyEntries = new HashSet<>(ENTRIES.keySet());
        emptyEntries.remove("entrc_seoul.txt");
        return zip(ENTRIES, emptyEntries, "테스트로");
    }

    static ByteArrayInputStream nationwideZipWithWrongProvince() throws IOException {
        Map<String, Province> entries = new LinkedHashMap<>(ENTRIES);
        entries.put("entrc_busan.txt", new Province("27", "대구광역시"));
        entries.put("entrc_daegu.txt", new Province("26", "부산광역시"));
        return zip(entries);
    }

    private static ByteArrayInputStream zip(Map<String, Province> entries) throws IOException {
        return zip(entries, Set.of(), "테스트로");
    }

    private static ByteArrayInputStream zip(
            Map<String, Province> entries,
            Set<String> emptyEntries,
            String seoulRoadName
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, Province> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                writeEntry(zip, entry, emptyEntries, seoulRoadName);
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static void writeEntry(
            ZipOutputStream zip,
            Map.Entry<String, Province> entry,
            Set<String> emptyEntries,
            String seoulRoadName
    ) throws IOException {
        if (emptyEntries.contains(entry.getKey())) {
            return;
        }
        Province province = entry.getValue();
        zip.write(row(province.code(), province.name(), seoulRoadName).getBytes(MS949));
    }

    private static String row(String provinceCode, String provinceName, String seoulRoadName) {
        String districtCode = provinceCode + "000";
        String districtName = "테스트시";
        if (provinceCode.equals("11")) {
            districtCode = "11110";
            districtName = "종로구";
        }
        String roadName = roadName(provinceCode, seoulRoadName);
        return String.join("|",
                districtCode,
                "1",
                districtCode + "00000",
                provinceName,
                districtName,
                "테스트동",
                districtCode + "0000001",
                roadName,
                "0",
                "1",
                "0",
                "건물",
                "12345",
                "시설",
                "0",
                "행정동",
                "953875.044172",
                "1951999.498732"
        );
    }

    private static String roadName(String provinceCode, String seoulRoadName) {
        if (provinceCode.equals("11")) {
            return seoulRoadName;
        }
        return "비대상로";
    }

    private static Map<String, Province> entries() {
        Map<String, Province> entries = new LinkedHashMap<>();
        entries.put("entrc_busan.txt", new Province("26", "부산광역시"));
        entries.put("entrc_chungbuk.txt", new Province("43", "충청북도"));
        entries.put("entrc_chungnam.txt", new Province("44", "충청남도"));
        entries.put("entrc_daegu.txt", new Province("27", "대구광역시"));
        entries.put("entrc_daejeon.txt", new Province("30", "대전광역시"));
        entries.put("entrc_gangwon.txt", new Province("51", "강원특별자치도"));
        entries.put("entrc_gyeongbuk.txt", new Province("47", "경상북도"));
        entries.put("entrc_gyeongnam.txt", new Province("48", "경상남도"));
        entries.put("entrc_gyunggi.txt", new Province("41", "경기도"));
        entries.put("entrc_incheon.txt", new Province("28", "인천광역시"));
        entries.put("entrc_jeju.txt", new Province("50", "제주특별자치도"));
        entries.put("entrc_jeonbuk.txt", new Province("52", "전북특별자치도"));
        entries.put("entrc_jeonnamgwangju.txt", new Province("12", "광주전남"));
        entries.put("entrc_sejong.txt", new Province("36", "세종특별자치시"));
        entries.put("entrc_seoul.txt", new Province("11", "서울특별시"));
        entries.put("entrc_ulsan.txt", new Province("31", "울산광역시"));
        return entries;
    }

    private record Province(String code, String name) {
    }
}
