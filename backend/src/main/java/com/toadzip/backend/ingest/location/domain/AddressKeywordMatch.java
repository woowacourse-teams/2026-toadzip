package com.toadzip.backend.ingest.location.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공고문에서 읽은 주소가 주소 검색 결과 한 건과 같은 곳인지 판정한다. 관리자가 후보를 고르지 않아도 되는지 정하는 데 쓴다.
 * 도로명주소는 (시도, 도로명, 건물번호), 지번주소는 (시도, 법정동·리, 산 여부, 번지)가 같으면 같은 곳으로 본다.
 * 시·도 약칭·옛 이름, 띄어쓰기, 괄호 속 참고항목과 건물명 차이는 무시한다. 시·도가 없는 주소는 같은 곳이라고 판정하지 않는다.
 */
public final class AddressKeywordMatch {

    private static final Pattern JIBUN = Pattern.compile("([가-힣0-9]+(?:동|리|가))\\s*(산\\s*)?([0-9]{1,6})(?:\\s*-\\s*([0-9]{1,6}))?(?![0-9])");

    private AddressKeywordMatch() {
    }

    public static boolean matches(String keyword, JusoAddress address) {
        Optional<RoadNumberAddress> road = RoadNumberAddress.parse(keyword);
        if (road.isPresent() && road.equals(RoadNumberAddress.parse(address.roadAddress()))) {
            return true;
        }
        Optional<Lot> lot = Lot.parse(keyword);
        return lot.isPresent() && lot.equals(Lot.parse(address.jibunAddress()));
    }

    private record Lot(String province, String dong, boolean mountain, int main, int sub) {

        static Optional<Lot> parse(String address) {
            Optional<String> province = RoadNumberAddress.province(address);
            if (province.isEmpty()) {
                return Optional.empty();
            }
            Matcher matcher = JIBUN.matcher(address.replaceAll("\\([^)]*\\)", " "));
            if (!matcher.find()) {
                return Optional.empty();
            }
            int sub = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            return Optional.of(new Lot(province.get(), matcher.group(1), matcher.group(2) != null,
                    Integer.parseInt(matcher.group(3)), sub));
        }

    }
}
