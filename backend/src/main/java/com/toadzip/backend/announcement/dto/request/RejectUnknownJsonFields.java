package com.toadzip.backend.announcement.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public interface RejectUnknownJsonFields {

    @JsonAnySetter
    default void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("정의되지 않은 JSON 필드입니다: " + name);
    }
}
