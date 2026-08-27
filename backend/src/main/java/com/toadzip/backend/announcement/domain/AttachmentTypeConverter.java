package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class AttachmentTypeConverter extends LegacyEnumAttributeConverter<AttachmentType> {

    @Override
    protected AttachmentType fromStoredValue(String databaseValue) {
        return AttachmentType.fromStoredValue(databaseValue);
    }
}
