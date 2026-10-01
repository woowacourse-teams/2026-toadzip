package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCurrentSourceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;
    private static final ExternalDataSource SUPPLY = ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;

    @Autowired
    private MyHomeAnnouncementSourceRepository sources;

    @Autowired
    private LhAnnouncementDetailSourceRepository details;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplies;

    @Autowired
    private LhAnnouncementCollectionLinkRepository links;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpoints;

    @Autowired
    private ExternalDataCollectionFailureRepository failures;

    @Autowired
    private LhAnnouncementCatalogSourceRepository catalog;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private SupplyRowRepository supplyRows;

    @Autowired
    private SupplyTargetRepository targets;

    @Autowired
    private AnnouncementScheduleRepository schedules;

    @Autowired
    private AnnouncementAttachmentRepository attachments;

    @Autowired
    private MyHomeAnnouncementMappingFailureRepository mappingFailures;

    @Autowired
    private LhAnnouncementEnrichmentFailureRepository enrichmentFailures;

    @Autowired
    private LhAnnouncementExternalCollectionService collection;

    @Autowired
    private MyHomeAnnouncementMappingService mapping;

    @Autowired
    private LhAnnouncementEnrichmentService enrichment;

    @Autowired
    private HousingComplexRepository complexes;

    @Autowired
    private HousingTypeRepository housingTypes;

    @MockitoBean
    private LhAnnouncementExternalRepository external;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        cleanUp();
        saveHousingComplex();
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(NOW);
        when(external.fetchDetail(any())).thenReturn(detailResponse());
        when(external.fetchSupply(any())).thenReturn(supplyResponse());
    }

    @AfterEach
    void cleanUp() {
        targets.deleteAllInBatch();
        supplyRows.deleteAllInBatch();
        schedules.deleteAllInBatch();
        attachments.deleteAllInBatch();
        announcements.deleteAllInBatch();
        housingTypes.deleteAllInBatch();
        complexes.deleteAllInBatch();
        mappingFailures.deleteAllInBatch();
        enrichmentFailures.deleteAllInBatch();
        links.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
        details.deleteAllInBatch();
        supplies.deleteAllInBatch();
        failures.deleteAllInBatch();
        catalog.deleteAllInBatch();
        sources.deleteAllInBatch();
    }

    @Test
    void 최신_행이_500행_배치_밖에_있어도_공급과_상세를_연결하고_현재_PAN으로_정제한다() {
        MyHomeAnnouncementSource previous = source("P1", 1, "100", "previous", NOW.minusSeconds(60));
        previous.markMissed();
        previous.markMissed();
        List<MyHomeAnnouncementSource> rows = new ArrayList<>();
        rows.add(previous);
        for (int index = 0; index < 499; index++) {
            MyHomeAnnouncementSource filler = source("filler-" + index, 1, "300", "old", NOW);
            filler.replaceWith(snapshot("filler-" + index, 1, "300", "20260101"));
            rows.add(filler);
        }
        rows.add(source("P1", 2, "200", "current", NOW));
        sources.saveAll(rows);

        assertThat(collection.collect(SUPPLY).failedRequestCount()).isZero();
        assertThat(collection.collect(DETAIL).failedRequestCount()).isZero();

        assertThat(links.findAll()).hasSize(2).allSatisfy(link -> {
            assertThat(link.getSourceAnnouncementKey()).isEqualTo("P1");
            assertThat(link.getPanId()).isEqualTo("200");
        });
        assertThat(details.findAll()).singleElement().satisfies(detail ->
                assertThat(detail.getPanId()).isEqualTo("200"));
        // 배치 경계용 공고는 수집 정책에서 제외했으며 정제 검증에는 P1만 남긴다.
        sources.deleteAll(sources.findAll().stream().filter(row -> !row.getPblancId().equals("P1")).toList());
        assertCurrentProduct("200");
    }

    @Test
    void 최신_요청_실패는_이전_원천과_성공_연결과_제품을_보존하고_재시도하면_정제까지_복구한다() {
        MyHomeAnnouncementSource previous = sources.save(source("P1", 1, "100", "previous", NOW.minusSeconds(60)));
        collection.collect(SUPPLY);
        collection.collect(DETAIL);
        assertCurrentProduct("100");
        Long announcementId = announcements.findAll().getFirst().getId();
        Long detailId = details.findAll().getFirst().getId();
        previous.markMissed();
        previous.markMissed();
        sources.save(previous);
        sources.save(source("P1", 2, "200", "current", NOW));
        when(external.fetchDetail(any())).thenThrow(new ExternalDataRequestException("최신 상세 조회 실패"));
        when(external.fetchSupply(any())).thenThrow(new ExternalDataRequestException("최신 공급 조회 실패"));

        assertThat(collection.refresh(SUPPLY, "P1").failedRequestCount()).isOne();
        assertThat(collection.refresh(DETAIL, "P1").failedRequestCount()).isOne();
        assertThat(links.findAll()).hasSize(2).allSatisfy(link -> assertThat(link.getPanId()).isEqualTo("100"));
        assertThat(checkpoints.findAll()).hasSize(2)
                .allSatisfy(checkpoint -> assertThat(checkpoint.getPanId()).isEqualTo("100"));
        assertThat(details.findAll()).singleElement().satisfies(row -> assertThat(row.getId()).isEqualTo(detailId));
        assertThat(mapping.mapAll().failedSourceRowCount()).isPositive();
        assertThat(enrichment.enrichAll().failedSourceCount()).isPositive();
        assertThat(announcements.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getId()).isEqualTo(announcementId);
            assertThat(announcement.getLhPanId()).isEqualTo("100");
        });

        doReturn(supplyResponse()).when(external).fetchSupply(any());
        doReturn(detailResponse()).when(external).fetchDetail(any());
        assertThat(collection.refresh(SUPPLY, "P1").failedRequestCount()).isZero();
        assertThat(collection.refresh(DETAIL, "P1").failedRequestCount()).isZero();
        assertCurrentProduct("200");
        assertThat(announcements.findAll()).singleElement()
                .extracting(Announcement::getId).isEqualTo(announcementId);
        assertThat(failures.findAll()).allSatisfy(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED));
    }

    @Test
    void 요청_충돌_실패는_반복해도_하나의_이력으로_남고_현재_원천_복구_후_해결된다() {
        MyHomeAnnouncementSource first = sources.save(source("P1", 1, "100", "current", NOW));
        sources.save(source("P1", 2, "200", "current", NOW));
        assertThat(collection.collect(DETAIL).failedRequestCount()).isOne();
        assertThat(collection.refresh(DETAIL, "P1").failedRequestCount()).isOne();
        assertThat(failures.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING));
        assertThat(links.count()).isZero();

        first.markMissed();
        first.markMissed();
        sources.save(first);
        assertThat(collection.refresh(DETAIL, "P1").failedRequestCount()).isZero();
        assertThat(links.findAll()).singleElement().satisfies(link -> assertThat(link.getPanId()).isEqualTo("200"));
        assertThat(failures.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "non_lh", "unsupported"})
    void 충돌_공고가_수집_대상에서_빠지면_기존_실패를_건너뜀으로_정리한다(String exclusion) {
        MyHomeAnnouncementSource first = sources.save(source("P1", 1, "100", "current", NOW));
        MyHomeAnnouncementSource second = sources.save(source("P1", 2, "200", "current", NOW));
        assertThat(collection.collect(DETAIL).failedRequestCount()).isOne();
        Long failureId = failures.findAll().getFirst().getId();
        excludeSource(first, exclusion);
        excludeSource(second, exclusion);
        sources.saveAll(List.of(first, second));

        var excluded = collection.collect(DETAIL);

        assertThat(excluded.failedRequestCount()).isZero();
        assertThat(excluded.externalApiCallCount()).isZero();
        assertThat(failures.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getId()).isEqualTo(failureId);
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.SKIPPED);
        });
        assertThat(links.count()).isZero();

        first.replaceWith(snapshot("P1", 1, "100", "20261030"));
        second.replaceWith(snapshot("P1", 2, "200", "20261030"));
        sources.saveAll(List.of(first, second));
        assertThat(collection.collect(DETAIL).failedRequestCount()).isOne();
        assertThat(failures.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getId()).isEqualTo(failureId);
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
            assertThat(failure.getRecurrenceCount()).isOne();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "non_lh", "unsupported"})
    void 일부_원천만_제외되고_충돌이나_수집_실패가_남으면_기존_보류를_유지한다(String exclusion) {
        MyHomeAnnouncementSource first = sources.save(source("P1", 1, "100", "current", NOW));
        sources.save(source("P1", 2, "200", "current", NOW));
        assertThat(collection.collect(DETAIL).failedRequestCount()).isOne();
        Long failureId = failures.findAll().getFirst().getId();
        excludeSource(first, exclusion);
        sources.save(first);
        when(external.fetchDetail(any())).thenThrow(new ExternalDataRequestException("현재 요청 수집 실패"));

        assertThat(collection.collect(DETAIL).failedRequestCount()).isOne();

        assertThat(failures.findById(failureId)).hasValueSatisfying(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING));
        assertThat(links.count()).isZero();
    }

    @Test
    void 공유_요청의_캐시가_유효하면_외부_호출_없이_각_공고의_충돌_이력을_해결한다() {
        sources.saveAll(List.of(source("P1", 1, "100", "current", NOW),
                source("P2", 1, "100", "current", NOW)));
        assertThat(collection.collect(DETAIL).externalApiCallCount()).isOne();
        Long firstFailure = pendingConflict(DETAIL, "P1");
        Long linkedFailure = pendingConflict(DETAIL, "P2");
        Long otherApiFailure = pendingConflict(SUPPLY, "P1");
        Long otherAnnouncementFailure = pendingConflict(DETAIL, "P3");

        var report = collection.collect(DETAIL);

        assertThat(report.failedRequestCount()).isZero();
        assertThat(report.externalApiCallCount()).isZero();
        assertFailureStatus(firstFailure, ExternalDataFailureStatus.RESOLVED);
        assertFailureStatus(linkedFailure, ExternalDataFailureStatus.RESOLVED);
        assertFailureStatus(otherApiFailure, ExternalDataFailureStatus.PENDING);
        assertFailureStatus(otherAnnouncementFailure, ExternalDataFailureStatus.PENDING);
        assertThat(checkpoints.findAll()).singleElement().satisfies(checkpoint ->
                assertThat(checkpoint.getCompletedAt()).isEqualTo(NOW));
    }

    @Test
    void 한_배치에서_성공한_공고의_충돌_이력만_해결하고_실패한_공고는_보존한다() {
        sources.saveAll(List.of(source("P1", 1, "100", "current", NOW),
                source("P2", 1, "200", "current", NOW)));
        Long successfulFailure = pendingConflict(DETAIL, "P1");
        Long unsuccessfulFailure = pendingConflict(DETAIL, "P2");
        when(external.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("200")) {
                throw new ExternalDataRequestException("두 번째 공고 수집 실패");
            }
            return detailResponse();
        });

        var report = collection.collect(DETAIL);

        assertThat(report.failedRequestCount()).isOne();
        assertFailureStatus(successfulFailure, ExternalDataFailureStatus.RESOLVED);
        assertFailureStatus(unsuccessfulFailure, ExternalDataFailureStatus.PENDING);
        assertThat(links.findAll()).singleElement().satisfies(link ->
                assertThat(link.getSourceAnnouncementKey()).isEqualTo("P1"));
    }

    private Long pendingConflict(ExternalDataSource targetSource, String identifier) {
        return failures.save(ExternalDataCollectionFailure.create(
                targetSource, "myhomeAnnouncementCurrentSource=" + identifier, NOW, 0,
                "IllegalStateException", "과거 원천 요청 충돌"
        )).getId();
    }

    private void assertFailureStatus(Long identifier, ExternalDataFailureStatus status) {
        assertThat(failures.findById(identifier)).hasValueSatisfying(failure ->
                assertThat(failure.getStatus()).isEqualTo(status));
    }

    private void excludeSource(MyHomeAnnouncementSource source, String exclusion) {
        switch (exclusion) {
            case "expired" -> ReflectionTestUtils.setField(source, "endDe", "20260801");
            case "non_lh" -> ReflectionTestUtils.setField(source, "suplyInsttNm", "SH");
            case "unsupported" -> ReflectionTestUtils.setField(source, "suplyTyNm", "미지원 유형");
            default -> throw new IllegalArgumentException("알 수 없는 수집 제외 조건: " + exclusion);
        }
    }

    private void saveHousingComplex() {
        Address address = Address.create("서울특별시 종로구 테스트로 1", "1111010100100010000",
                "1111010100", "11", "11110", new BigDecimal("37.566206"), new BigDecimal("126.977706"));
        HousingComplex complex = complexes.save(HousingComplex.createFromMyHome(
                "테스트 단지", "test:HAPPY_HOUSING", "HAPPY_HOUSING", address, 100, "LH",
                null, "DISTRICT", "APARTMENT", "CORRIDOR", true, 80
        ));
        housingTypes.save(HousingType.createFromMyHome(complex, "type-46A", "46A",
                new BigDecimal("46.8000"), new BigDecimal("67.0000")));
    }

    private void assertCurrentProduct(String panId) {
        assertThat(mapping.mapAll().failedSourceRowCount())
                .as("매핑 실패: %s", mappingFailures.findAll().stream().map(failure -> failure.getDetail()).toList())
                .isZero();
        assertThat(enrichment.enrichAll().failedSourceCount()).isZero();
        assertThat(announcements.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getLhPanId()).isEqualTo(panId));
    }

    private MyHomeAnnouncementSource source(
            String identifier, int houseSn, String panId, String runId, Instant seenAt
    ) {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0,
                snapshot(identifier, houseSn, panId, "20261030"));
        source.markSeen(runId, seenAt);
        return source;
    }

    private MyHomeAnnouncementSourceSnapshot snapshot(String identifier, int houseSn, String panId, String endDate) {
        String payload = """
                {"pblancId":"%s","houseSn":%d,"suplyInsttNm":"LH","suplyTyNm":"행복주택",
                 "pblancNm":"테스트 공고","sttusNm":"모집중","houseTyNm":"46A","hsmpNm":"테스트 단지",
                 "rcritPblancDe":"20260901","beginDe":"20260920","endDe":"%s","przwnerPresnatnDe":"20261130",
                 "brtcNm":"서울특별시","signguNm":"종로구","sumSuplyCo":10,"pnu":"1111010100100010000",
                 "url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(identifier, houseSn, endDate, panId);
        return JsonMapper.builder().build().readValue(payload, MyHomeAnnouncementSourceSnapshot.class);
    }

    private JsonNode detailResponse() {
        return response("""
                [{"resHeader":[{"SS_CODE":"Y"}],"dsSbd":[{"LCC_NT_NM":"테스트 단지"}]}]
                """);
    }

    private JsonNode supplyResponse() {
        return response("""
                [{"resHeader":[{"SS_CODE":"Y"}],"dsList01":[]}]
                """);
    }

    private JsonNode response(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
