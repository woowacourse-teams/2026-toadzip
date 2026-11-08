package com.toadzip.backend.announcement.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;

@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class ReceptionPlace {

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

    /** 안내 문구를 빼고 같은 접수처인지. LH 원천은 안내 문구를 주지 않아, 관리자가 붙인 안내만으로 원천 변경으로 보지 않는다. */
    boolean isSamePlace(ReceptionPlace other) {
        return other != null
                && name.equals(other.name)
                && method == other.method
                && Objects.equals(address, other.address)
                && Objects.equals(contact, other.contact)
                && Objects.equals(url, other.url);
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
