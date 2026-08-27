package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ReceptionMethodConverter extends LegacyEnumAttributeConverter<ReceptionMethod> {

    @Override
    protected ReceptionMethod fromStoredValue(String databaseValue) {
        return ReceptionMethod.fromStoredValue(databaseValue);
    }
}
