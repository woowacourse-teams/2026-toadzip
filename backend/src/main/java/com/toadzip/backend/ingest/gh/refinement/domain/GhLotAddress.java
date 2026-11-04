package com.toadzip.backend.ingest.gh.refinement.domain;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GH 지구 소재지(지번 주소)의 단지 미리 고르기 키: (시군구, 산 여부, 본번, 부번). 제품 단지는 도로명 주소와 PNU만 있고 동 이름 표가
 * 없으므로 동은 비교하지 않는다. 소재지 뒤에 붙는 단지명·괄호 도로명은 버리고, 여러 필지(`일원`, `외 N필지`)나 블록처럼 한 지번으로 정할 수
 * 없는 소재지는 키를 만들지 않는다.
 */
public record GhLotAddress(String cityCountyDistrict, boolean mountain, int mainNumber, int subNumber) {

    private static final Pattern LOT = Pattern.compile("^(산)?(\\d{1,4})(?:-(\\d{1,4}))?$");
    private static final Pattern UNRESOLVABLE = Pattern.compile("일원|필지|블록|\\s외\\s");
    private static final int PNU_LENGTH = 19;
    private static final char MOUNTAIN_PNU = '2';

    public static Optional<GhLotAddress> parse(String location) {
        if (location == null || UNRESOLVABLE.matcher(location).find()) {
            return Optional.empty();
        }
        List<String> tokens = List.of(location.replaceAll("\\(.*", " ").strip().split("\\s+"));
        if (tokens.size() < 3 || !tokens.get(1).matches(".+[시군구]")) {
            return Optional.empty();
        }
        for (int index = 2; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("산") && index + 1 < tokens.size()) {
                token = "산" + tokens.get(index + 1);
            }
            Matcher lot = LOT.matcher(token);
            if (lot.matches() && tokens.get(index - 1).matches(".+[동읍면리가]|산")) {
                return Optional.of(new GhLotAddress(tokens.get(1), lot.group(1) != null,
                        Integer.parseInt(lot.group(2)), Integer.parseInt(nonNull(lot.group(3)))));
            }
        }
        return Optional.empty();
    }

    /** PNU(법정동 10자리 + 산 여부 1자리 + 본번 4자리 + 부번 4자리)의 산 여부·본번·부번이 이 지번과 같은지 본다. */
    public boolean matchesPnu(String pnu) {
        if (pnu == null || pnu.length() != PNU_LENGTH || !pnu.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return (pnu.charAt(10) == MOUNTAIN_PNU) == mountain
                && Integer.parseInt(pnu.substring(11, 15)) == mainNumber
                && Integer.parseInt(pnu.substring(15)) == subNumber;
    }

    private static String nonNull(String subNumber) {
        if (subNumber == null) {
            return "0";
        }
        return subNumber;
    }
}
