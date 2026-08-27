package com.toadzip.backend.global.persistence;

import jakarta.persistence.AttributeConverter;

public abstract class LegacyEnumAttributeConverter<T extends Enum<T>> implements AttributeConverter<T, String> {

    @Override
    public String convertToDatabaseColumn(T attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name();
    }

    @Override
    public T convertToEntityAttribute(String databaseValue) {
        if (databaseValue == null) {
            return null;
        }
        return fromStoredValue(databaseValue);
    }

    protected abstract T fromStoredValue(String databaseValue);
}
