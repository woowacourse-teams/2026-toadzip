package com.toadzip.backend.announcement.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
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

    @Column(nullable = false)
    private String contact;

    private String url;

    private ReceptionPlace(String name, ReceptionMethod method, String address, String contact, String url) {
        validateNotBlank(name, "접수처명");
        validateRequired(method, "접수방식");
        validateNotBlank(contact, "연락처");
        this.name = name;
        this.method = method;
        this.address = address;
        this.contact = contact;
        this.url = url;
    }

    public static ReceptionPlace create(String name, ReceptionMethod method, String address, String contact, String url) {
        return new ReceptionPlace(name, method, address, contact, url);
    }

    public static ReceptionPlace create(String name, String method, String address, String contact, String url) {
        return create(name, ReceptionMethod.fromStoredValue(method), address, contact, url);
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
