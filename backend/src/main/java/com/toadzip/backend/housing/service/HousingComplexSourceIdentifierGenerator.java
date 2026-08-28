package com.toadzip.backend.housing.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class HousingComplexSourceIdentifierGenerator {

    private static final String PREFIX = "ADMIN_ENTRY-HOUSING-COMPLEX-";

    public String generate() {
        return PREFIX + UUID.randomUUID();
    }
}
