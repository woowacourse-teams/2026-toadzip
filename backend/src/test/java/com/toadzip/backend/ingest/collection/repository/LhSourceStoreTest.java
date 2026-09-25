package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhLeaseCatalogResponseParser;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhSourceStoreTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-25T01:00:00Z");

    @Autowired
    private LhCatalogSourceRepository catalogRepository;

    @Autowired
    private LhAnnouncementDetailSourceRepository detailRepository;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplyRepository;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    private LhSourceStore store;

    @BeforeEach
    void setUp() {
        store = new LhSourceStore(
                catalogRepository,
                detailRepository,
                supplyRepository,
                checkpointRepository,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void LH_카탈로그_응답_항목을_각각_테이블_행으로_저장한다() {
        List<LhCatalogSourceSnapshot> snapshots = List.of(
                catalog("강릉교동 행복주택", "36.97"),
                catalog("강릉교동 행복주택", "44.12")
        );

        int storedRowCount = store.replaceCatalog(snapshots);

        assertThat(storedRowCount).isEqualTo(2);
        assertThat(catalogRepository.findAll())
                .allSatisfy(source -> assertThat(source.getCollectedAt()).isEqualTo(COLLECTED_AT))
                .extracting(source -> source.getExclusiveArea())
                .containsExactly("36.97", "44.12");
    }

    @Test
    void 빈_LH_카탈로그_응답은_기존_snapshot을_삭제하지_않는다() {
        store.replaceCatalog(List.of(catalog("강릉교동 행복주택", "36.97")));

        assertThatThrownBy(() -> store.replaceCatalog(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalogRepository.count()).isOne();
    }

    @Test
    void 내용_없는_카탈로그_응답은_기존_원천을_보존한다() {
        store.replaceCatalog(List.of(catalog("강릉교동 행복주택", "36.97")));
        String payload = "[{\"dsList\":[{}]}]";
        ExternalDataResponse response = new ExternalDataResponse(payload,
                JsonMapper.builder().build().readTree(payload));

        assertThatThrownBy(() -> new LhLeaseCatalogResponseParser().parse(response))
                .isInstanceOf(ExternalDataRequestException.class);

        assertThat(catalogRepository.findAll()).singleElement()
                .extracting(source -> source.getComplexLabel()).isEqualTo("강릉교동 행복주택");
    }

    @Test
    void LH_상세와_공급은_panId별로_각각_독립된_테이블에_저장한다() {
        LhAnnouncementDetailSource detail = new LhAnnouncementDetailSource(
                0, "PAN-1", "ETC_INFO", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "정정", "공고문 확인"
        );
        LhAnnouncementSupplySource supply = new LhAnnouncementSupplySource(
                0,
                "PAN-1",
                new LhAnnouncementSupplySourceSnapshot(
                        "순천선평3",
                        "24(일반)",
                        "24.71",
                        "37.9268",
                        "240",
                        "50",
                        null,
                        null
                )
        );

        store.replaceDetails("PAN-1", "PAN_ID=PAN-1&TYPE=DETAIL", List.of(detail));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=SUPPLY", List.of(supply));

        assertThat(detailRepository.findAll()).singleElement()
                .extracting(source -> source.getDatasetType())
                .isEqualTo("ETC_INFO");
        assertThat(supplyRepository.findAll()).singleElement()
                .extracting(source -> source.getComplexLabel())
                .isEqualTo("순천선평3");
    }

    @Test
    void LH_상세_교체는_같은_조회_조건의_행만_삭제한다() {
        String firstRequest = "PAN_ID=PAN-1&TYPE=A";
        String otherRequest = "PAN_ID=PAN-1&TYPE=B";
        store.replaceDetails("PAN-1", firstRequest, List.of(detail("이전")));
        store.replaceDetails("PAN-1", otherRequest, List.of(detail("다른 조건")));

        store.replaceDetails("PAN-1", firstRequest, List.of(detail("새 값")));

        String firstHash = LhAnnouncementCollectionCheckpoint.requestHashOf(firstRequest);
        String otherHash = LhAnnouncementCollectionCheckpoint.requestHashOf(otherRequest);
        assertThat(detailRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc("PAN-1", firstHash))
                .extracting(LhAnnouncementDetailSource::getCorrectionReason).containsExactly("새 값");
        assertThat(detailRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc("PAN-1", otherHash))
                .extracting(LhAnnouncementDetailSource::getCorrectionReason).containsExactly("다른 조건");
    }

    @Test
    void 빈_상세_결과로_기존_원천을_삭제하지_않는다() {
        String request = "PAN_ID=PAN-1&TYPE=DETAIL";
        store.replaceDetails("PAN-1", request, List.of(detail("기존 정정 사유")));

        assertThatThrownBy(() -> store.replaceDetails("PAN-1", request, List.of()))
                .isInstanceOf(EmptyLhDetailReplacementException.class);

        assertThat(detailRepository.findAll()).singleElement()
                .extracting(LhAnnouncementDetailSource::getCorrectionReason)
                .isEqualTo("기존 정정 사유");
    }

    @Test
    void 내용_없는_상세_응답은_기존_원천과_성공_체크포인트를_보존한다() {
        String request = "PAN_ID=PAN-1&TYPE=DETAIL";
        store.replaceDetails("PAN-1", request, List.of(detail("기존 정정 사유")));
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "announcement", request, "PAN-1", COLLECTED_AT));

        assertThatThrownBy(() -> new LhAnnouncementDetailResponseParser().parse("PAN-1",
                JsonMapper.builder().build().readTree("[{\"dsSbd\":[{}]}]")))
                .isInstanceOf(ExternalDataRequestException.class);

        assertThat(detailRepository.findAll()).singleElement()
                .extracting(LhAnnouncementDetailSource::getCorrectionReason).isEqualTo("기존 정정 사유");
        assertThat(checkpointRepository.count()).isOne();
    }

    @Test
    void 최초_빈_상세_결과는_허용한다() {
        assertThat(store.replaceDetails("PAN-1", "PAN_ID=PAN-1&TYPE=DETAIL", List.of())).isZero();
        assertThat(detailRepository.count()).isZero();
    }

    @Test
    void 수집_버전만_바뀐_빈_상세_결과는_이전_성공_원천을_보존한다() {
        String previousRequest = "PAN_ID=PAN-1&TYPE=DETAIL&COLLECTION_VERSION=5";
        store.replaceDetails("PAN-1", previousRequest, List.of(detail("이전 정정 사유")));
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "announcement", previousRequest, "PAN-1", COLLECTED_AT));

        assertThatThrownBy(() -> store.replaceDetails("PAN-1",
                previousRequest.replace("VERSION=5", "VERSION=6"), List.of()))
                .isInstanceOf(EmptyLhDetailReplacementException.class);
        assertThat(detailRepository.findAll()).singleElement()
                .extracting(LhAnnouncementDetailSource::getCorrectionReason)
                .isEqualTo("이전 정정 사유");
    }

    @Test
    void 같은_panId의_다른_조회_조건이_기존_원천을_덮지_않는다() {
        LhAnnouncementSupplySource first = new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "첫 번째 단지", "24", "24", "30", "10", "5", null, null
                )
        );
        LhAnnouncementSupplySource second = new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "두 번째 단지", "36", "36", "45", "20", "8", null, null
                )
        );

        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=A", List.of(first));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=B", List.of(second));
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=A", List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "첫 번째 단지", "24", "24", "30", "10", "6", null, null
                )
        )));

        assertThat(supplyRepository.findAll()).hasSize(2);
        assertThat(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "PAN-1", LhAnnouncementCollectionCheckpoint.requestHashOf("PAN_ID=PAN-1&TYPE=A")
        )).singleElement().extracting(LhAnnouncementSupplySource::getComplexLabel)
                .isEqualTo("첫 번째 단지");
        assertThat(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "PAN-1", LhAnnouncementCollectionCheckpoint.requestHashOf("PAN_ID=PAN-1&TYPE=B")
        )).singleElement().extracting(LhAnnouncementSupplySource::getComplexLabel).isEqualTo("두 번째 단지");
    }

    @Test
    void 빈_공급_결과로_같은_요청의_기존_원천을_교체하지_않는다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "기존 단지", "24", "24", "30", "100", "20", "10000000", "200000"
                )
        )));
        List<LhAnnouncementSupplySource> previous = supplyRepository.findAll();

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of()))
                .isInstanceOf(EmptyLhSupplyReplacementException.class)
                .hasMessage("기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(previous);
    }

    @Test
    void 다른_요청의_원천이_있어도_최초_빈_공급_결과를_허용하고_기존_원천을_보존한다() {
        store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=A", List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "기존 단지", "24", "24", "30", "100", "20", null, null
                )
        )));
        List<LhAnnouncementSupplySource> previous = supplyRepository.findAll();

        assertThat(store.replaceSupplies("PAN-1", "PAN_ID=PAN-1&TYPE=B", List.of())).isZero();

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(previous);
    }

    @Test
    void 기존_150행_중_100행만_수집되면_기존_전체_원천을_보존한다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, IntStream.range(0, 150)
                .mapToObj(index -> supply(index, "단지-" + index, "24", "24.0", "30.0"))
                .toList());
        var previous = supplyRepository.findAll();

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request,
                IntStream.range(0, 100)
                        .mapToObj(index -> supply(index, "단지-" + index, "24", "24.0", "30.0"))
                        .toList()))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class)
                .hasMessageContaining("누락");

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"다른 단지", "중복 행", "전용면적", "공급면적"})
    void 전체_건수가_같아도_기존_공급행이_빠지면_교체하지_않는다(String changed) {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "나 단지", "24", "24", "30")));
        var previous = supplyRepository.findAll();
        LhAnnouncementSupplySource replacement = switch (changed) {
            case "다른 단지" -> supply(1, "다 단지", "24", "24", "30");
            case "중복 행" -> supply(1, "가 단지", "24", "24", "30");
            case "전용면적" -> supply(1, "나 단지", "24", "25", "30");
            default -> supply(1, "나 단지", "24", "24", "31");
        };

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "가 단지", "24", "24", "30"), replacement)))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class)
                .hasMessageContaining("누락");

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previous);
    }

    @Test
    void 같은_식별값의_중복행도_개수가_감소하면_교체하지_않는다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "가 단지", "24", "24", "30")));

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "가 단지", "24", "24", "30"))))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class)
                .hasMessageContaining("누락");
        assertThat(supplyRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"20, 250000", "25, 200000"})
    void 같은_식별값의_다른_세대수나_금액_행이_중복_응답에_가려지면_교체하지_않는다(
            String secondSuppliedUnitCount,
            String secondRent
    ) {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(
                supplyWithValues(0, "20", "200000"),
                supplyWithValues(1, secondSuppliedUnitCount, secondRent)));
        var previous = supplyRepository.findAll();

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of(
                supplyWithValues(0, "20", "200000"),
                supplyWithValues(1, "20", "200000"))))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class)
                .hasMessageContaining("기존 공급행 1건이 누락");

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previous);
    }

    @Test
    void 같은_식별값의_다른_금액_행이_순서만_바뀌면_교체한다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(
                supplyWithValues(0, "20", "200000"),
                supplyWithValues(1, "20", "250000")));

        assertThat(store.replaceSupplies("PAN-1", request, List.of(
                supplyWithValues(0, "20", "250000"),
                supplyWithValues(1, "20", "200000")))).isEqualTo(2);

        assertThat(supplyRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "PAN-1", LhAnnouncementCollectionCheckpoint.requestHashOf(request)))
                .extracting(LhAnnouncementSupplySource::getMonthlyRentText)
                .containsExactly("250000", "200000");
    }

    @Test
    void 수집_버전만_바뀐_첫_부분_응답도_이전_성공_원천과_비교한다() {
        String previousRequest = "PAN_ID=PAN-1&TYPE=A&COLLECTION_VERSION=3";
        store.replaceSupplies("PAN-1", previousRequest, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "나 단지", "24", "24", "30")));
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement", previousRequest, "PAN-1", COLLECTED_AT));
        var previous = supplyRepository.findAll();

        assertThatThrownBy(() -> store.replaceSupplies(
                "PAN-1", previousRequest.replace("VERSION=3", "VERSION=4"),
                List.of(supply(0, "가 단지", "24", "24", "30"))))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class)
                .hasMessageContaining("누락");

        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previous);
    }

    @ParameterizedTest
    @CsvSource({
            "SPL_INF_TP_CD=062, SPL_INF_TP_CD=064",
            "AIS_TP_CD=07, AIS_TP_CD=06",
            "CCR_CNNT_SYS_DS_CD=03, CCR_CNNT_SYS_DS_CD=02",
            "UPP_AIS_TP_CD=06, UPP_AIS_TP_CD=05"
    })
    void 실제_조회_조건이_다른_이전_원천은_누락_비교에_섞지_않는다(String from, String to) {
        String previousRequest = "PAN_ID=PAN-1&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06"
                + "&SPL_INF_TP_CD=062&AIS_TP_CD=07&COLLECTION_VERSION=3";
        store.replaceSupplies("PAN-1", previousRequest, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "나 단지", "24", "24", "30")));
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement", previousRequest, "PAN-1", COLLECTED_AT));
        String currentRequest = previousRequest.replace(from, to).replace("VERSION=3", "VERSION=4");

        assertThat(store.replaceSupplies("PAN-1", currentRequest,
                List.of(supply(0, "새 단지", "24", "24", "30")))).isOne();
        assertThat(supplyRepository.count()).isEqualTo(3);
    }

    @Test
    void 구버전에서_주택형_없이_저장한_원천도_누락_검사에서_제외하지_않는다() {
        String previousRequest = "PAN_ID=PAN-1&TYPE=A&COLLECTION_VERSION=3";
        store.replaceSupplies("PAN-1", previousRequest, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "나 단지", null, "36", "45")));
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement", previousRequest, "PAN-1", COLLECTED_AT));
        var previous = supplyRepository.findAll();

        assertThatThrownBy(() -> store.replaceSupplies(
                "PAN-1", previousRequest.replace("VERSION=3", "VERSION=4"),
                List.of(supply(0, "가 단지", "24", "24", "30"))))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class);
        assertThat(supplyRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previous);
    }

    @Test
    void 순서와_숫자표기와_세대수와_금액_변경_및_행_추가는_허용한다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "가 단지", "24", "24", "30"),
                supply(1, "나 단지", "36", "36", "45")));

        assertThat(store.replaceSupplies("PAN-1", request, List.of(
                supply(0, "나 단지", "36", "36.000", "45.0㎡"),
                new LhAnnouncementSupplySource(1, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "가 단지", "24", "24.00", "30.0", "110", "0", "12000000", "250000")),
                supply(2, "다 단지", "46", "46", "60")))).isEqualTo(3);
        assertThat(supplyRepository.findAll()).filteredOn(source -> "가 단지".equals(source.getComplexLabel()))
                .singleElement().satisfies(source -> {
                    assertThat(source.getSuppliedUnitCount()).isEqualTo("0");
                    assertThat(source.getMonthlyRentText()).isEqualTo("250000");
                });
    }

    private LhAnnouncementSupplySource supply(int order, String complex, String type, String area, String supplyArea) {
        return new LhAnnouncementSupplySource(order, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                complex, type, area, supplyArea, "100", "20", "10000000", "200000"));
    }

    private LhAnnouncementSupplySource supplyWithValues(int order, String suppliedUnitCount, String rent) {
        return new LhAnnouncementSupplySource(order, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                "가 단지", "24", "24", "30", "100", suppliedUnitCount, "10000000", rent));
    }

    @Test
    void 응답의_panId가_요청과_다르면_기존_원천을_보존한다() {
        String request = "PAN_ID=PAN-1&TYPE=A";
        store.replaceSupplies("PAN-1", request, List.of(new LhAnnouncementSupplySource(
                0, "PAN-1", new LhAnnouncementSupplySourceSnapshot(
                        "기존 단지", "24", "24", "30", "10", "5", null, null
                )
        )));

        assertThatThrownBy(() -> store.replaceSupplies("PAN-1", request, List.of(
                new LhAnnouncementSupplySource(0, "PAN-2", new LhAnnouncementSupplySourceSnapshot(
                        "잘못된 단지", "36", "36", "45", "20", "8", null, null
                ))
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThat(supplyRepository.findAll()).singleElement()
                .extracting(LhAnnouncementSupplySource::getComplexLabel).isEqualTo("기존 단지");
    }

    private LhCatalogSourceSnapshot catalog(String label, String area) {
        return new LhCatalogSourceSnapshot(
                "강원특별자치도 강릉시", "행복주택", label, "180", area, "72", "0", "0"
        );
    }

    private LhAnnouncementDetailSource detail(String correctionReason) {
        return new LhAnnouncementDetailSource(
                0, "PAN-1", "ETC_INFO", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, correctionReason, "공고문 확인"
        );
    }
}
