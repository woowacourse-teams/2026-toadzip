package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class InterestJpaMappingTest {

    private static final String DOMAIN_PACKAGE = "com.toadzip.backend.interest.domain.";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 관심_도메인을_JPA_엔티티로_등록한다() {
        assertEntityAttributes(
                "FavoriteNotice",
                Set.of("id", "user", "notice", "createdAt")
        );
        assertEntityAttributes(
                "FavoriteHousingComplex",
                Set.of("id", "user", "housingComplex", "createdAt")
        );
        assertEntityAttributes(
                "FavoriteRegion",
                Set.of(
                        "id",
                        "user",
                        "provinceCode",
                        "provinceName",
                        "cityCountyDistrictCode",
                        "cityCountyDistrictName",
                        "createdAt"
                )
        );
    }

    private void assertEntityAttributes(String simpleClassName, Set<String> expectedAttributes) {
        Class<?> entityClass = assertDoesNotThrow(() -> Class.forName(DOMAIN_PACKAGE + simpleClassName));
        EntityType<?> entityType = assertDoesNotThrow(() -> entityManagerFactory.getMetamodel().entity(entityClass));
        Set<String> actualAttributes = entityType.getAttributes()
                .stream()
                .map(Attribute::getName)
                .collect(Collectors.toSet());

        assertEquals(expectedAttributes, actualAttributes);
    }
}
