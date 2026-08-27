package com.toadzip.backend.housing.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class AgencyCodeConverter extends LegacyEnumAttributeConverter<AgencyCode> {

    @Override
    protected AgencyCode fromStoredValue(String databaseValue) {
        return AgencyCode.fromStoredValue(databaseValue);
    }
}
