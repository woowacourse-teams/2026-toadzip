package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AnnouncementJpaMappingTest {

    private static final String DOMAIN_PACKAGE = "com.toadzip.backend.announcement.domain.";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 공고_도메인을_JPA_관리_타입으로_등록한다() {
        assertEntityAttributes(
                "Announcement",
                Set.of(
                        "id",
                        "sourceAnnouncementIdentifier",
                        "previousSourceAnnouncementIdentifier",
                        "previousAnnouncement",
                        "name",
                        "status",
                        "supplyType",
                        "recruitmentType",
                        "provider",
                        "postedDate",
                        "applicationStartDate",
                        "applicationEndDate",
                        "winnerAnnouncementDate",
                        "originalUrl",
                        "correctionCancellationReason",
                        "viewCount",
                        "actualCompetitionRate",
                        "predictedCompetitionRate",
                        "receptionPlace"
                )
        );
        assertEntityAttributes(
                "SupplyRow",
                Set.of(
                        "id",
                        "announcement",
                        "housingComplex",
                        "housingType",
                        "sourceSupplyRowIdentifier",
                        "displayOrder",
                        "sourceComplexName",
                        "sourceHousingTypeName",
                        "supplyPnu",
                        "expectedMoveInMonth",
                        "supplyCategory",
                        "matchingFailureReason",
                        "totalSupplyHouseholdCount"
                )
        );
        assertEntityAttributes(
                "SupplyTarget",
                Set.of(
                        "id",
                        "supplyRow",
                        "target",
                        "supplyRank",
                        "supplyHouseholdCount",
                        "reserveCount",
                        "rentalDeposit",
                        "monthlyRent",
                        "convertedDeposit",
                        "applicationCondition",
                        "displayOrder"
                )
        );
        assertEntityAttributes(
                "AnnouncementSchedule",
                Set.of("id", "announcement", "scheduleType", "name", "startAt", "endAt", "displayOrder")
        );
        assertEntityAttributes(
                "AnnouncementAttachment",
                Set.of("id", "announcement", "fileName", "fileType", "fileUrl", "displayOrder")
        );
        assertEmbeddableAttributes(
                "ReceptionPlace",
                Set.of("name", "method", "address", "contact", "url")
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
