package com.toadzip.backend.announcement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.YearMonth;

@Converter
public class YearMonthAttributeConverter implements AttributeConverter<YearMonth, String> {

    @Override
    public String convertToDatabaseColumn(YearMonth attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.toString();
    }

    @Override
    public YearMonth convertToEntityAttribute(String databaseData) {
        if (databaseData == null) {
            return null;
        }
        return YearMonth.parse(databaseData);
    }
}
