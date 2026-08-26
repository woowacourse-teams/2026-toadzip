package com.toadzip.backend.ingest.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalDataSource;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementExternalCollectionEntryPointTest {

    @Mock
    private LhAnnouncementExternalCollectionService collectionService;

    @Test
    void 상세_수집_진입점은_LH_상세_API만_선택한다() {
        LhAnnouncementDetailCollectionService detailCollectionService =
                new LhAnnouncementDetailCollectionService(collectionService);

        detailCollectionService.collect();

        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
    }

    @Test
    void 공급_수집_진입점은_LH_공급_API만_선택한다() {
        LhAnnouncementSupplyCollectionService supplyCollectionService =
                new LhAnnouncementSupplyCollectionService(collectionService);

        supplyCollectionService.collect();

        verify(collectionService).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
    }
}
