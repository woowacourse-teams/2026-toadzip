package com.toadzip.backend.announcement.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class ReceptionPlace {

    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)");
    // 도로명(…로·…길, '성남대로 54번길'처럼 번길까지)과 건물번호. 번호 뒤에 '번길'이 붙으면 도로명의 일부라 건물번호가 아니다.
    private static final Pattern ROAD_ADDRESS = Pattern.compile(
            "([가-힣A-Za-z0-9]+(?:로|길)(?:\\s*\\d+번?길)?)\\s*(\\d+(?:-\\d+)?)(?![\\d가-힣])");
    private static final Pattern URL_PREFIX = Pattern.compile("^(?:https?://)?(?:www\\.)?");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern NOT_DIGIT = Pattern.compile("\\D");

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Convert(converter = ReceptionMethodConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private ReceptionMethod method;

    private String address;

    private String contact;

    private String url;

    /** 이 접수처의 안내 한 줄(예: 고령자 등 인터넷 취약계층만 현장 접수). 접수 기간은 공고 일정에 둔다. */
    @Column(length = 1000)
    private String guidance;

    private ReceptionPlace(String name, ReceptionMethod method, String address, String contact, String url,
            String guidance) {
        validateNotBlank(name, "접수처명");
        validateRequired(method, "접수방식");
        this.name = name;
        this.method = method;
        this.address = address;
        this.contact = contact;
        this.url = url;
        this.guidance = guidance == null || guidance.isBlank() ? null : guidance;
    }

    public static ReceptionPlace create(String name, ReceptionMethod method, String address, String contact, String url,
            String guidance) {
        return new ReceptionPlace(name, method, address, contact, url, guidance);
    }

    public static ReceptionPlace create(String name, ReceptionMethod method, String address, String contact, String url) {
        return create(name, method, address, contact, url, null);
    }

    public static ReceptionPlace create(String name, String method, String address, String contact, String url) {
        return create(name, ReceptionMethod.fromStoredValue(method), address, contact, url, null);
    }

    /**
     * 같은 곳의 접수처인지. 접수 방식이 같아야 하고, 둘 다 주소가 있으면 도로명+건물번호(없으면 괄호를 뺀 주소)로, 둘 다 URL이 있으면
     * 호스트로, 그 밖에는 띄어쓰기·기호를 뺀 이름으로 가린다. LH 원천과 공고문은 같은 곳을 다르게 적으므로(이름이 없거나 다름, 주소 끝
     * 괄호 속 단지명, URL 끝 '/') 이름·안내 문구·연락처 표기 차이만으로 다른 곳으로 보지 않는다. PDF 보정 화면도 같은 기준으로 짝짓는다.
     */
    boolean isSamePlace(ReceptionPlace other) {
        if (other == null || method != other.method) {
            return false;
        }
        if (present(address) && present(other.address)) {
            return addressKey(address).equals(addressKey(other.address));
        }
        if (present(url) && present(other.url)) {
            return urlHost(url).equals(urlHost(other.url));
        }
        return compact(name).equals(compact(other.name));
    }

    /**
     * LH 원천 접수처가 저장된 목록에 그대로 있는지(관리자가 고친 공고의 원천 변경 확인). 같은 곳이어야 하고, 연락처가 둘 다 있으면
     * 숫자가 같아야 한다. 같은 건물에서 전화번호만 바뀌어도 옛 번호가 공개 화면에 남지 않게 확인을 켠다.
     */
    boolean isSameSourcePlace(ReceptionPlace other) {
        if (!isSamePlace(other)) {
            return false;
        }
        if (!present(contact) || !present(other.contact)) {
            return true;
        }
        return digits(contact).equals(digits(other.contact));
    }

    private static String digits(String value) {
        return NOT_DIGIT.matcher(value).replaceAll("");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String addressKey(String value) {
        String withoutParentheses = PARENTHESES.matcher(value).replaceAll(" ");
        Matcher road = ROAD_ADDRESS.matcher(withoutParentheses);
        if (road.find()) {
            return compact(road.group(1)) + road.group(2);
        }
        return compact(withoutParentheses);
    }

    private static String urlHost(String value) {
        String host = URL_PREFIX.matcher(value.strip().toLowerCase(Locale.ROOT)).replaceFirst("");
        int end = host.indexOf('/');
        if (end < 0) {
            return host;
        }
        return host.substring(0, end);
    }

    private static String compact(String value) {
        return NOT_LETTER_OR_DIGIT.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    boolean hasSameValues(ReceptionPlace other) {
        return other != null
                && name.equals(other.name)
                && method == other.method
                && Objects.equals(address, other.address)
                && Objects.equals(contact, other.contact)
                && Objects.equals(url, other.url)
                && Objects.equals(guidance, other.guidance);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
