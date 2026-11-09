package com.toadzip.backend.ingest.location.service;

import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.nationwideZipWithEmptyRegions;
import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.nationwideZipWithWrongProvince;
import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.partialZip;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.repository.LocationSummaryStore;
import com.toadzip.backend.ingest.location.repository.external.LocationSummaryFileParser;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(IngestExecutionExtension.class)
@ExtendWith(MockitoExtension.class)
class LocationSummaryImportServiceTest {

    @Mock
    private MyHomeInitialRegistrationService registration;

    @Mock
    private LocationSummaryStore locationStore;

    @Mock
    private MyHomeComplexSourceReader sourceRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private LocationSummaryImportService service;

    @BeforeEach
    void setUp() {
        when(sourceRepository.findDistinctRoadAddresses())
                .thenReturn(List.of("서울특별시 종로구 테스트로 1 (테스트동)"));
        service = new LocationSummaryImportService(
                new LocationSummaryFileParser(), locationStore, sourceRepository,
                transactionTemplate, registration, new UtmKCoordinateConverter()
        );
    }

    @Test
    void 일부_시도만_있는_ZIP은_저장_전에_거절한다() throws IOException {
        assertThatThrownBy(() -> service.importMatches("partial.zip", partialZip()))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("전국 월전체분이 아닙니다");

        verifyNoInteractions(locationStore, transactionTemplate);
    }

    @Test
    void 빈_지역_TXT가_있는_ZIP은_저장_전에_거절한다() throws IOException {
        assertThatThrownBy(() -> service.importMatches("empty-region.zip", nationwideZipWithEmptyRegions()))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("비어 있거나 시도코드가 다른 파일");

        verifyNoInteractions(locationStore, transactionTemplate);
    }

    @Test
    void 잘못된_시도코드의_ZIP은_저장_전에_거절한다() throws IOException {
        assertThatThrownBy(() -> service.importMatches("wrong-region.zip", nationwideZipWithWrongProvince()))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("entrc_busan.txt")
                .hasMessageContaining("entrc_daegu.txt");

        verifyNoInteractions(locationStore, transactionTemplate);
    }
}
