package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolver;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MyHomeAnnouncementSupplyRowResolverTest {

    private final LhAnnouncementLinkResolver linkResolver = mock(LhAnnouncementLinkResolver.class);
    private final LhAnnouncementSupplySourceRepository supplyRepository =
            mock(LhAnnouncementSupplySourceRepository.class);
    private final MyHomeAnnouncementSupplyRowResolver resolver =
            new MyHomeAnnouncementSupplyRowResolver(linkResolver, supplyRepository);

    @ParameterizedTest
    @CsvSource({
            "중동한라1단지, 중동한라10단지 영구임대주택",
            "광명1단지 10블록, 광명110블록"
    })
    void 번호_구성이나_숫자_토큰_경계가_다른_LH_공급행을_연결하지_않는다(
            String myHomeComplexName,
            String lhComplexName
    ) {
        MyHomeAnnouncementSource source = mock(MyHomeAnnouncementSource.class);
        MyHomeSupplyRowMappingData sourceRow = new MyHomeSupplyRowMappingData(
                source,
                "myhome-row",
                myHomeComplexName,
                "기존 주택형",
                "4119010800100010000",
                "PERMANENT_RENTAL",
                SupplyCategory.NEW_SUPPLY,
                null,
                null,
                null,
                null,
                null
        );
        MyHomeAnnouncementMappingData data = new MyHomeAnnouncementMappingData(
                "announcement",
                null,
                "공고",
                null,
                null,
                null,
                AgencyCode.LH,
                null,
                null,
                null,
                null,
                "https://example.com",
                null,
                List.of(sourceRow),
                false
        );
        when(linkResolver.resolve(source)).thenReturn("pan-id");
        when(supplyRepository.findAllByPanIdOrderBySourceOrderAsc("pan-id"))
                .thenReturn(List.of(lhSupply(lhComplexName)));

        MyHomeAnnouncementMappingData result = resolver.resolve(data);

        assertThat(result.supplyRows()).containsExactly(sourceRow);
    }

    private LhAnnouncementSupplySource lhSupply(String complexName) {
        return new LhAnnouncementSupplySource(
                0,
                "pan-id",
                new LhAnnouncementSupplySourceSnapshot(
                        complexName,
                        "LH 주택형",
                        "26.37",
                        "39.12",
                        "925",
                        "150",
                        null,
                        null
                )
        );
    }
}
