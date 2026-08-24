package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.toadzip.backend.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgreSqlIntegrationTest
class HousingJpaMappingTest {

    private static final String DOMAIN_PACKAGE = "com.toadzip.backend.housing.domain.";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 단지_도메인을_JPA_관리_타입으로_등록한다() {
        assertEntityAttributes(
                "HousingComplex",
                Set.of(
                        "id",
                        "name",
                        "sourceComplexIdentifier",
                        "supplyType",
                        "address",
                        "totalHouseholdCount",
                        "provider",
                        "completionDate",
                        "heatingType",
                        "housingType",
                        "corridorType",
                        "elevatorInstalled",
                        "parkingSpaceCount",
                        "imageUrl",
                        "recentOneYearMoveOutCount"
                )
        );
        assertEntityAttributes(
                "HousingType",
                Set.of(
                        "id",
                        "housingComplex",
                        "name",
                        "exclusiveArea",
                        "supplyArea",
                        "totalHouseholdCount",
                        "floorPlanUrl",
                        "duplex",
                        "maintenanceFee"
                )
        );
        assertEmbeddableAttributes(
                "Address",
                Set.of(
                        "roadAddress",
                        "pnu",
                        "legalDongCode",
                        "provinceCode",
                        "cityCountyDistrictCode",
                        "latitude",
                        "longitude"
                )
        );
    }

    private void assertEntityAttributes(String simpleClassName, Set<String> expectedAttributes) {
        Class<?> entityClass = loadClass(simpleClassName);
        ManagedType<?> managedType = assertDoesNotThrow(
                () -> entityManagerFactory.getMetamodel().entity(entityClass)
        );

        assertEquals(expectedAttributes, attributeNames(managedType));
    }

    private void assertEmbeddableAttributes(String simpleClassName, Set<String> expectedAttributes) {
        Class<?> embeddableClass = loadClass(simpleClassName);
        ManagedType<?> managedType = assertDoesNotThrow(
                () -> entityManagerFactory.getMetamodel().embeddable(embeddableClass)
        );

        assertEquals(expectedAttributes, attributeNames(managedType));
    }

    private Class<?> loadClass(String simpleClassName) {
        return assertDoesNotThrow(() -> Class.forName(DOMAIN_PACKAGE + simpleClassName));
    }

    private Set<String> attributeNames(ManagedType<?> managedType) {
        return managedType.getAttributes()
                .stream()
                .map(Attribute::getName)
                .collect(Collectors.toSet());
    }
}
