package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementLinkResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.projection.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplySourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementLinkResolutionException;
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
    private final LhAnnouncementSupplySourceReader supplyRepository =
            mock(LhAnnouncementSupplySourceReader.class);
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
                "past-pan", LhAnnouncementQuery.requestHashOf(pastRequest.requestDescription())))
                .thenReturn(List.of(lhSupply("같은 단지", "past-pan", "46A")));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "current-pan", LhAnnouncementQuery.requestHashOf(currentRequest.requestDescription())))
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
                "pan-id", LhAnnouncementQuery.requestHashOf(request.requestDescription())))
                .thenReturn(List.of(lhSupply(lhComplexName)));

        MyHomeAnnouncementMappingData result = resolver.resolve(data).data();

        assertThat(result.supplyRows()).containsExactly(sourceRow);
    }

    // 로컬 DB #45 아산읍내 영구임대(공고 21381): 마이홈 원천 1행 240, LH 공급 2행 200+40
    @Test
    void 아산읍내_마이홈_원천_한_행을_단지명_번호가_붙은_LH_공급행으로_나눈다() {
        var result = resolveSingleSource("아산읍내", 240, List.of(
                lhSupply(0, "아산읍내1 영구임대아파트", "26", "26.37", "38.54", "200"),
                lhSupply(1, "아산읍내1 영구임대아파트", "45.3400", "31.32", "45.34", "40")
        ));

        assertThat(result).extracting(MyHomeSupplyRowMappingData::sourceHousingTypeName)
                .containsExactly("26", "45.3400");
        assertThat(result).extracting(MyHomeSupplyRowMappingData::totalSupplyHouseholdCount)
                .containsExactly(200, 40);
    }

    // 로컬 DB #4 의령동동 행복주택(공고 21424): 마이홈 원천 1행 73, LH 공급 3행 50+8+15
    @Test
    void 의령동동_마이홈_원천_한_행을_블록_번호가_붙은_LH_공급행으로_나눈다() {
        var result = resolveSingleSource("의령동동", 73, List.of(
                lhSupply(0, "의령동동 5블록 행복주택", "16형", "16.64", "26.8722", "50"),
                lhSupply(1, "의령동동 5블록 행복주택", "26형 고령자(주거약자용)", "26.11", "42.1655", "8"),
                lhSupply(2, "의령동동 5블록 행복주택", "26형 주거급여수급자", "26.15", "42.2301", "15")
        ));

        assertThat(result).extracting(MyHomeSupplyRowMappingData::sourceHousingTypeName)
                .containsExactly("16형", "26형 고령자(주거약자용)", "26형 주거급여수급자");
        assertThat(result).extracting(MyHomeSupplyRowMappingData::totalSupplyHouseholdCount)
                .containsExactly(50, 8, 15);
    }

    @Test
    void 단지명이_다른_LH_공급행의_모집수_합계가_원천과_다르면_나누지_않는다() {
        var result = resolveSingleSource("아산읍내", 240, List.of(
                lhSupply(0, "아산읍내1 영구임대아파트", "26", "26.37", "38.54", "200"),
                lhSupply(1, "아산읍내1 영구임대아파트", "45.3400", "31.32", "45.34", "30")
        ));

        assertThat(result).extracting(MyHomeSupplyRowMappingData::sourceHousingTypeName).containsExactly("아파트");
    }

    @Test
    void 단지명이_다른_LH_공급행의_모집수를_알_수_없으면_나누지_않는다() {
        var result = resolveSingleSource("아산읍내", 240, List.of(
                lhSupply(0, "아산읍내1 영구임대아파트", "26", "26.37", "38.54", "240"),
                lhSupply(1, "아산읍내1 영구임대아파트", "45.3400", "31.32", "45.34", null)
        ));

        assertThat(result).extracting(MyHomeSupplyRowMappingData::sourceHousingTypeName).containsExactly("아파트");
    }

    // 로컬 DB #41 전주평화1·4(공고 19585): 1단지는 단지명으로 잇고, 4단지는 LH 표기가 달라 남은 한 행끼리 모집수로 잇는다.
    @Test
    void 단지명으로_잇고_남은_원천_한_행은_남은_LH_공급행과_모집수가_같으면_잇는다() {
        var result = resolveSources(List.of(new Source("평화주공1단지", 450), new Source("평화주공4단지", 300)), List.of(
                lhSupply(0, "전주평화4 영구임대주택", "39.6011", "26.37", "38.9756", "300"),
                lhSupply(1, "전주평화주공1단지", "39.8800", "26.37", "39.85", "450")
        ));

        assertThat(result).extracting(MyHomeSupplyRowMappingData::sourceComplexName, MyHomeSupplyRowMappingData::sourceHousingTypeName,
                MyHomeSupplyRowMappingData::resolvedLhSourceIdentifier).containsExactly(
                tuple("평화주공1단지", "39.8800", "LH:pan-id:SUPPLY:1"),
                tuple("평화주공4단지", "39.6011", "LH:pan-id:SUPPLY:0"));
    }

    @Test
    void 남은_원천이_둘_이상이거나_남은_모집수가_다르면_추정해_잇지_않는다() {
        var twoLeft = resolveSources(List.of(new Source("평화주공4단지", 300), new Source("평화주공5단지", 300)), List.of(
                lhSupply(0, "전주평화4 영구임대주택", "39.6011", "26.37", "38.9756", "300")
        ));
        var differentCount = resolveSources(List.of(new Source("평화주공1단지", 450), new Source("평화주공4단지", 300)), List.of(
                lhSupply(0, "전주평화4 영구임대주택", "39.6011", "26.37", "38.9756", "299"),
                lhSupply(1, "전주평화주공1단지", "39.8800", "26.37", "39.85", "450")
        ));

        assertThat(twoLeft).extracting(MyHomeSupplyRowMappingData::resolvedLhSourceIdentifier).containsOnlyNulls();
        assertThat(differentCount).extracting(MyHomeSupplyRowMappingData::resolvedLhSourceIdentifier)
                .containsExactly("LH:pan-id:SUPPLY:1", null);
    }

    private record Source(String complexName, int householdCount) { }

    private List<MyHomeSupplyRowMappingData> resolveSources(List<Source> sources,
            List<LhAnnouncementSupplySource> lhSupplies) {
        List<MyHomeAnnouncementSource> mocks = new java.util.ArrayList<>();
        List<MyHomeSupplyRowMappingData> rows = new java.util.ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            MyHomeAnnouncementSource source = mock(MyHomeAnnouncementSource.class);
            when(source.isActive()).thenReturn(true);
            mocks.add(source);
            rows.add(new MyHomeSupplyRowMappingData(source, "myhome-row-" + index, sources.get(index).complexName(),
                    "아파트", "4420011000102060000", "PERMANENT_RENTAL", SupplyCategory.NEW_SUPPLY,
                    sources.get(index).householdCount(), null, null, null, null));
        }
        LhAnnouncementRequest request = new LhAnnouncementRequest("pan-id", "03", "06", "07", "062");
        when(linkResolver.resolveFirstLinked(mocks))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(mocks.getFirst(), request));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "pan-id", LhAnnouncementQuery.requestHashOf(request.requestDescription())))
                .thenReturn(lhSupplies);
        return resolver.resolve(data(rows)).data().supplyRows();
    }

    private List<MyHomeSupplyRowMappingData> resolveSingleSource(
            String myHomeComplexName,
            int householdCount,
            List<LhAnnouncementSupplySource> lhSupplies
    ) {
        MyHomeAnnouncementSource source = mock(MyHomeAnnouncementSource.class);
        when(source.isActive()).thenReturn(true);
        MyHomeSupplyRowMappingData sourceRow = new MyHomeSupplyRowMappingData(
                source, "myhome-row", myHomeComplexName, "아파트", "4420011000102060000",
                "PERMANENT_RENTAL", SupplyCategory.NEW_SUPPLY, householdCount, null, null, null, null
        );
        LhAnnouncementRequest request = new LhAnnouncementRequest("pan-id", "03", "06", "07", "062");
        when(linkResolver.resolveFirstLinked(List.of(source)))
                .thenReturn(new LhAnnouncementLinkResolver.LinkedSource(source, request));
        when(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "pan-id", LhAnnouncementQuery.requestHashOf(request.requestDescription())))
                .thenReturn(lhSupplies);
        return resolver.resolve(data(List.of(sourceRow))).data().supplyRows();
    }

    private LhAnnouncementSupplySource lhSupply(int order, String complexName, String typeName,
            String exclusiveArea, String supplyArea, String suppliedCount) {
        return new LhAnnouncementSupplySource(order, "pan-id", new LhAnnouncementSupplySourceSnapshot(
                complexName, typeName, exclusiveArea, supplyArea, null, suppliedCount, null, null));
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
