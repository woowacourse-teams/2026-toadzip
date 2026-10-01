package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolutionException;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MyHomeAnnouncementSupplyRowResolverTest {

    private final LhAnnouncementLinkResolver linkResolver = mock(LhAnnouncementLinkResolver.class);
    private final LhAnnouncementSupplySourceRepository supplyRepository =
            mock(LhAnnouncementSupplySourceRepository.class);
    private final MyHomeAnnouncementSupplyRowResolver resolver =
            new MyHomeAnnouncementSupplyRowResolver(linkResolver, supplyRepository);

    @Test
    void 현재_원천의_LH_연결로_공급행을_해석한다() {
        MyHomeAnnouncementSource past = mock(MyHomeAnnouncementSource.class);
        MyHomeAnnouncementSource current = mock(MyHomeAnnouncementSource.class);
        when(current.isActive()).thenReturn(true);
        MyHomeSupplyRowMappingData pastRow = sourceRow(past, "past-row", "같은 단지");
        MyHomeSupplyRowMappingData currentRow = sourceRow(current, "current-row", "같은 단지");
        LhAnnouncementRequest pastRequest = new LhAnnouncementRequest("past-pan", "03", "06", "07", "062");
        LhAnnouncementRequest currentRequest = new LhAnnouncementRequest("current-pan", "03", "06", "07", "062");
        when(linkResolver.resolveFirstLinked(List.of(past, current)))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(past, pastRequest));
        when(linkResolver.resolveFirstLinked(List.of(current)))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(current, currentRequest));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "past-pan", LhAnnouncementCollectionCheckpoint.requestHashOf(pastRequest.requestDescription())))
                .thenReturn(List.of(lhSupply("같은 단지", "past-pan", "46A")));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "current-pan", LhAnnouncementCollectionCheckpoint.requestHashOf(currentRequest.requestDescription())))
                .thenReturn(List.of(lhSupply("같은 단지", "current-pan", "59A")));

        MyHomeAnnouncementMappingData result = resolver.resolve(data(List.of(pastRow, currentRow))).data();

        assertThat(result.supplyRows()).extracting(MyHomeSupplyRowMappingData::sourceHousingTypeName)
                .containsExactly("기존 주택형", "59A");
        assertThat(result.supplyRows()).extracting(MyHomeSupplyRowMappingData::resolvedLhSourceIdentifier)
                .containsExactly(null, "LH:current-pan:SUPPLY:0");
    }

    @Test
    void 현재_원천의_연결이_없으면_과거_연결로_대체하지_않는다() {
        MyHomeAnnouncementSource past = mock(MyHomeAnnouncementSource.class);
        MyHomeAnnouncementSource current = mock(MyHomeAnnouncementSource.class);
        when(current.isActive()).thenReturn(true);
        LhAnnouncementRequest pastRequest = new LhAnnouncementRequest("past-pan", "03", "06", "07", "062");
        when(linkResolver.resolveFirstLinked(List.of(past, current)))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(past, pastRequest));
        when(linkResolver.resolveFirstLinked(List.of(current)))
                .thenThrow(new LhAnnouncementLinkResolutionException(
                        LhAnnouncementLinkResolutionException.Reason.LINK_NOT_FOUND, "현재 연결 없음"));

        assertThatThrownBy(() -> resolver.resolve(data(List.of(
                sourceRow(past, "past-row", "과거 단지"),
                sourceRow(current, "current-row", "현재 단지")
        )))).isInstanceOfSatisfying(MyHomeAnnouncementMappingRejectedException.class,
                exception -> assertThat(exception.reason())
                        .isEqualTo(MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_NOT_FOUND));
    }

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
                List.of(sourceRow)
        );
        LhAnnouncementRequest request = new LhAnnouncementRequest("pan-id", "03", "06", "07", "062");
        when(linkResolver.resolveFirstLinked(List.of(source)))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(source, request));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "pan-id", LhAnnouncementCollectionCheckpoint.requestHashOf(request.requestDescription())))
                .thenReturn(List.of(lhSupply(lhComplexName)));

        MyHomeAnnouncementMappingData result = resolver.resolve(data).data();

        assertThat(result.supplyRows()).containsExactly(sourceRow);
    }

    private LhAnnouncementSupplySource lhSupply(String complexName) {
        return lhSupply(complexName, "pan-id", "LH 주택형");
    }

    private LhAnnouncementSupplySource lhSupply(String complexName, String panId, String typeName) {
        return new LhAnnouncementSupplySource(
                0,
                panId,
                new LhAnnouncementSupplySourceSnapshot(
                        complexName,
                        typeName,
                        "26.37",
                        "39.12",
                        "925",
                        "150",
                        null,
                        null
                )
        );
    }

    private MyHomeSupplyRowMappingData sourceRow(
            MyHomeAnnouncementSource source,
            String identifier,
            String complexName
    ) {
        return new MyHomeSupplyRowMappingData(
                source, identifier, complexName, "기존 주택형", "4119010800100010000",
                "PERMANENT_RENTAL", SupplyCategory.NEW_SUPPLY, null, null, null, null, null
        );
    }

    private MyHomeAnnouncementMappingData data(List<MyHomeSupplyRowMappingData> rows) {
        return new MyHomeAnnouncementMappingData(
                "announcement", null, "공고", null, null, null, AgencyCode.LH,
                null, null, null, null, "https://example.com", null, rows
        );
    }
}
