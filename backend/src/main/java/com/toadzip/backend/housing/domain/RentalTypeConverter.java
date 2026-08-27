package com.toadzip.backend.housing.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class RentalTypeConverter extends LegacyEnumAttributeConverter<RentalType> {

    @Override
    protected RentalType fromStoredValue(String databaseValue) {
        return RentalType.fromStoredValue(databaseValue);
    }
}
