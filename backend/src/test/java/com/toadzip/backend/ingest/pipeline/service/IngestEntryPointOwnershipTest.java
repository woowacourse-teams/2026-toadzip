package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.collection.sh.service.ShAnnouncementCollectionService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.lhrefinement.service.LhAnnouncementRefinementService;
import com.toadzip.backend.ingest.location.service.LocationSummaryImportService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMergeService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 수집·정제 진입점마다 실행 소유권 없이 부르면 협력 객체를 쓰기 전에 거절하는지 확인한다. */
class IngestEntryPointOwnershipTest {

    static Stream<Arguments> entryPoints() {
        return Stream.of(
                Arguments.of(LhAnnouncementCatalogCollectionService.class, "collect", 0),
                Arguments.of(LhLeaseCatalogCollectionService.class, "collect", 1),
                Arguments.of(LhAnnouncementExternalCollectionService.class, "collect", 1),
                Arguments.of(LhAnnouncementExternalCollectionService.class, "refresh", 3),
                Arguments.of(LhAnnouncementExternalCollectionService.class, "refreshCatalogTarget", 2),
                Arguments.of(MyHomeAnnouncementCollectionService.class, "collect", 1),
                Arguments.of(MyHomeComplexCollectionService.class, "collect", 1),
                Arguments.of(ShAnnouncementCollectionService.class, "collect", 0),
                Arguments.of(LhHousingTypeHouseholdEnrichmentService.class, "enrichAll", 0),
                Arguments.of(LocationSummaryImportService.class, "importMatches", 2),
                Arguments.of(MyHomeComplexMappingService.class, "mapAll", 0),
                Arguments.of(MyHomeComplexMergeService.class, "merge", 2),
                Arguments.of(MyHomeComplexMergeService.class, "revert", 2),
                Arguments.of(LhAnnouncementRefinementService.class, "refineAll", 0)
        );
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("entryPoints")
    void 실행_소유권_없이_부른_진입점은_거절한다(Class<?> service, String name, int parameterCount) throws Exception {
        Method entryPoint = Arrays.stream(service.getMethods())
                .filter(method -> method.getName().equals(name) && method.getParameterCount() == parameterCount)
                .findFirst().orElseThrow();
        // 협력 객체가 없으므로 소유권을 확인하기 전에 다른 일을 하면 거절 대신 다른 예외가 난다.
        Object instance = mock(service, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> invoke(entryPoint, instance))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("수집·정제는 실행 소유권 안에서만 실행할 수 있습니다.");
    }

    private void invoke(Method entryPoint, Object instance) throws Throwable {
        try {
            entryPoint.invoke(instance, new Object[entryPoint.getParameterCount()]);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
