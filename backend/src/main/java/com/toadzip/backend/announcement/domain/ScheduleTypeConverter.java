package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ScheduleTypeConverter extends LegacyEnumAttributeConverter<ScheduleType> {

    @Override
    protected ScheduleType fromStoredValue(String databaseValue) {
        return ScheduleType.fromStoredValue(databaseValue);
    }
}
