package com.toadzip.backend.user.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UserJpaMappingTest {

    private static final String DOMAIN_PACKAGE = "com.toadzip.backend.user.domain.";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 유저_도메인을_JPA_엔티티로_등록한다() {
        assertEntityAttributes(
                "User",
                Set.of("id", "loginIdentifier", "createdAt")
        );
        assertEntityAttributes(
                "UserEligibilityInfo",
                Set.of(
                        "userId",
                        "user",
                        "birthDate",
                        "currentResidenceRegion",
                        "headOfHousehold",
                        "householdMemberCount",
                        "singleParentFamily",
                        "nonHomeowner",
                        "maritalStatus",
                        "marriageDate",
                        "spouseMonthlyIncome",
                        "childCount",
                        "student",
                        "employed",
                        "personalAverageMonthlyIncome",
                        "householdAverageMonthlyIncome",
                        "parentsAverageMonthlyIncome",
                        "totalAssets",
                        "vehicleValue",
                        "housingSubscriptionAccount",
                        "housingSubscriptionAccountJoinDate",
                        "housingSubscriptionPaymentCount",
                        "housingBenefitRecipient",
                        "basicLivingRecipient",
                        "disabled",
                        "veteran",
                        "newbornBirthDate"
                )
        );
        assertTableName("UserEligibilityInfo", "user_eligibility_infos");
        assertEntityAttributes(
                "UserPlace",
                Set.of("id", "user", "name", "address", "latitude", "longitude", "createdAt")
        );
    }

    private void assertTableName(String simpleClassName, String expectedTableName) {
        Class<?> entityClass = assertDoesNotThrow(() -> Class.forName(DOMAIN_PACKAGE + simpleClassName));
        Table table = entityClass.getAnnotation(Table.class);

        assertEquals(expectedTableName, table.name());
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
