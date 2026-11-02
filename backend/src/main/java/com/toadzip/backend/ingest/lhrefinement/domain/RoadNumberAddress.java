package com.toadzip.backend.ingest.lhrefinement.domain;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공고 단지 자동 연결의 주소 키: (시도, 도로명, 건물번호). 구·시군구 표기는 비교하지 않는다(화성 `동탄구` 생략 흡수).
 * 건물번호가 없는 주소(준공 전 블록 등)는 키를 만들지 않는다.
 */
public record RoadNumberAddress(String province, String road, String buildingNumber) {

    private static final Pattern ROAD = Pattern.compile("([가-힣A-Za-z0-9·.]+(?:로|길))\\s*([0-9]+(?:-[0-9]+)?)");
    /** 시도 표기 앞부분과 그 시도. 긴 앞부분부터 비교한다(`충청북` 다음 `충북`). 특별자치도 표기도 앞부분으로 흡수한다. */
    private static final List<ProvincePrefix> PROVINCES = List.of(
            new ProvincePrefix("충청북", "충북"), new ProvincePrefix("충청남", "충남"),
            new ProvincePrefix("전라북", "전북"), new ProvincePrefix("전라남", "전남"),
            new ProvincePrefix("경상북", "경북"), new ProvincePrefix("경상남", "경남"),
            new ProvincePrefix("서울", "서울"), new ProvincePrefix("부산", "부산"), new ProvincePrefix("대구", "대구"),
            new ProvincePrefix("인천", "인천"), new ProvincePrefix("광주", "광주"), new ProvincePrefix("대전", "대전"),
            new ProvincePrefix("울산", "울산"), new ProvincePrefix("세종", "세종"), new ProvincePrefix("경기", "경기"),
            new ProvincePrefix("강원", "강원"), new ProvincePrefix("충북", "충북"), new ProvincePrefix("충남", "충남"),
            new ProvincePrefix("전북", "전북"), new ProvincePrefix("전남", "전남"), new ProvincePrefix("경북", "경북"),
            new ProvincePrefix("경남", "경남"), new ProvincePrefix("제주", "제주"));

    public static Optional<RoadNumberAddress> parse(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String value = address.strip();
        Optional<String> province = PROVINCES.stream().filter(prefix -> value.startsWith(prefix.prefix()))
                .map(ProvincePrefix::province).findFirst();
        Matcher road = ROAD.matcher(value);
        if (province.isEmpty() || !road.find()) {
            return Optional.empty();
        }
        return Optional.of(new RoadNumberAddress(province.get(), road.group(1), road.group(2)));
    }

    private record ProvincePrefix(String prefix, String province) {
    }
}
