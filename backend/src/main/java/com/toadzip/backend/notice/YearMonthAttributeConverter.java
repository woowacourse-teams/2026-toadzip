package com.toadzip.backend.notice;

import java.time.YearMonth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link YearMonth}를 {@code YYYY-MM} 형식의 문자열로 저장한다.
 */
@Converter(autoApply = true)
public class YearMonthAttributeConverter implements AttributeConverter<YearMonth, String> {

	@Override
	public String convertToDatabaseColumn(YearMonth attribute) {
		if (attribute == null) {
			return null;
		}
		return attribute.toString();
	}

	@Override
	public YearMonth convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return YearMonth.parse(dbData);
	}

}
