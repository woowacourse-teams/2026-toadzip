package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class RecruitmentTypeConverter extends LegacyEnumAttributeConverter<RecruitmentType> {

    @Override
    protected RecruitmentType fromStoredValue(String databaseValue) {
        return RecruitmentType.fromStoredValue(databaseValue);
    }
}
