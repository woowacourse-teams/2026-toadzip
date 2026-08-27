package com.toadzip.backend.housing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class HousingComplexSourceIdentifierGeneratorTest {

    @Test
    void 관리자_수기_단지_원천_식별자를_UUID로_생성한다() {
        HousingComplexSourceIdentifierGenerator generator = new HousingComplexSourceIdentifierGenerator();

        String identifier = generator.generate();

        assertThat(identifier).startsWith("ADMIN_ENTRY-HOUSING-COMPLEX-");
        String uuidText = identifier.substring("ADMIN_ENTRY-HOUSING-COMPLEX-".length());
        assertEquals(uuidText, UUID.fromString(uuidText).toString());
    }
}
