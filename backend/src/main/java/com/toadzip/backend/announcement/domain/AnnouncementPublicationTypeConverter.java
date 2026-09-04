package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyEnumAttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class AnnouncementPublicationTypeConverter
        extends LegacyEnumAttributeConverter<AnnouncementPublicationType> {

    @Override
    protected AnnouncementPublicationType fromStoredValue(String databaseValue) {
        return AnnouncementPublicationType.fromStoredValue(databaseValue);
    }
}
