package com.toadzip.backend.ingest.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApi;

@ExtendWith(MockitoExtension.class)
class LhNoticeApiCollectionEntryPointTest {

    @Mock
    private LhNoticeApiCollectionService collectionService;

    @Test
    void 상세_수집_진입점은_LH_상세_API만_선택한다() {
        LhNoticeDetailCollectionService detailCollectionService =
                new LhNoticeDetailCollectionService(collectionService);

        detailCollectionService.collect();

        verify(collectionService).collect(ExternalApi.LH_NOTICE_DETAIL);
    }

    @Test
    void 공급_수집_진입점은_LH_공급_API만_선택한다() {
        LhNoticeSupplyCollectionService supplyCollectionService =
                new LhNoticeSupplyCollectionService(collectionService);

        supplyCollectionService.collect();

        verify(collectionService).collect(ExternalApi.LH_NOTICE_SUPPLY);
    }
}
