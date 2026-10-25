package com.toadzip.backend.housing.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class HousingComplexSourceIdentifierGenerator {

    public String generate() {
        return HousingComplex.ADMIN_SOURCE_PREFIX + UUID.randomUUID();
    }
}
