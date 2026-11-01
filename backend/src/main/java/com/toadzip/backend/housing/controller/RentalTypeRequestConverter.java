package com.toadzip.backend.housing.controller;

import com.toadzip.backend.housing.domain.RentalType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** 요청 파라미터의 공급유형. 합치기 전 공공임대 5·10년 코드로 저장한 링크·즐겨찾기도 공공임대로 받는다. */
@Component
public class RentalTypeRequestConverter implements Converter<String, RentalType> {

    @Override
    public RentalType convert(String source) {
        String code = source.trim();
        // 기본 enum 변환처럼 빈 값은 필터 없음으로 둔다.
        return code.isEmpty() ? null : RentalType.fromCode(code);
    }
}
