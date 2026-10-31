package com.toadzip.backend.ingest.collection.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.projection.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogApiRepository;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogPageParser;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollector;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogStorageService;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.api.LhLeaseCatalogCollectionCommand;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogPageParser;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollector;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogStorageService;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementQueryCollector;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementStorageService;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhSupplyResponseParser;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionCommand;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementPageParser;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollector;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementLifecycleService;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementStorageService;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeRegion;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionCommand;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexPageParser;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollector;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexRegionCollectionService;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexStorageService;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import tools.jackson.databind.json.JsonMapper;

/** 배치 조율 테스트는 새 Collector·검증 버퍼를 실행하고 HTTP와 DB 경계만 대체한다. */
public final class CollectionServiceTestFixture {

    static final Clock CLOCK = Clock.systemUTC();
    private final JsonMapper mapper = JsonMapper.builder().build();
    public final SourceCollectionRecordService records = mock(SourceCollectionRecordService.class);
    private final Map<UUID, Integer> storedCounts = new ConcurrentHashMap<>();

    /** 운영 수집과 같은 재시도·예외 변환 흐름을 재시도 대기 없이 실행한다. */
    public static ExternalDataRetryExecutor retryWithoutDelay() {
        return new ExternalDataRetryExecutor(Duration.ZERO, new SimpleMeterRegistry());
    }

    public CollectionServiceTestFixture() {
        lenient().when(records.start(any())).thenAnswer(invocation -> UUID.randomUUID());
        lenient().when(records.storedRowCount(any())).thenAnswer(
                invocation -> storedCounts.get(invocation.<UUID>getArgument(0)));
    }

    MyHomeComplexRegionCollectionService complex(
            MyHomeComplexExternalRepository external,
            MyHomeRegionCatalog regions,
            MyHomeComplexStorageService storage,
            ExternalDataFailureRecorder failures, ExternalDataRetryExecutor retry
    ) {
        var api = mock(MyHomeComplexApiRepository.class);
        var parser = new MyHomeComplexPageParser(mapper);
        lenient().when(api.fetch(any(), anyInt())).thenAnswer(invocation -> {
            MyHomeComplexCollectionRequest request = invocation.getArgument(0);
            var region = region(regions, request.provinceCode(), request.districtCode());
            var oldRequest = new MyHomeComplexCollectionCommand(
                    request.provinceCode(), request.districtCode(), request.pageSize(), request.maxPages());
            if (regions.find(request.provinceCode(), request.districtCode()) == null) {
                oldRequest = MyHomeComplexCollectionCommand.allRegions(
                        request.pageSize(), request.maxPages());
            }
            return parser.parse(external.fetch(region, oldRequest, invocation.getArgument(1)));
        });
        lenient().doAnswer(invocation -> {
            var response = invocation.<MyHomeComplexCollectedResponse>getArgument(2);
            storedCounts.put(invocation.getArgument(0), response.rows().size());
            return null;
        }).when(storage).complete(any(), any(), any());
        var collector = new MyHomeComplexCollector(
                storage, records, api, regions, CLOCK);
        return new MyHomeComplexRegionCollectionService(
                collector, records, failures, retry, CLOCK);
    }

    MyHomeAnnouncementCollectionService announcement(
            MyHomeAnnouncementExternalRepository external,
            MyHomeAnnouncementStorageService storage, MyHomeAnnouncementLifecycleService lifecycle,
            ExternalDataFailureRecorder failures, ExternalDataRetryExecutor retry
    ) {
        var api = mock(MyHomeAnnouncementApiRepository.class);
        var parser = new MyHomeAnnouncementPageParser(mapper);
        lenient().when(api.fetch(any(), anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementCollectionRequest request = invocation.getArgument(0);
            var type = Arrays.stream(MyHomeAnnouncementSupplyType.values())
                    .filter(value -> value.requestCode().equals(request.supplyTypeCode())).findFirst().orElseThrow();
            var oldRequest = new MyHomeAnnouncementCollectionCommand(
                    request.pageSize(), request.maxPages());
            return parser.parse(external.fetch(type, oldRequest, invocation.getArgument(1)));
        });
        lenient().doAnswer(invocation -> {
            var response = invocation.<MyHomeAnnouncementCollectedResponse>getArgument(2);
            storedCounts.put(invocation.getArgument(0), response.rows().size());
            return null;
        }).when(storage).complete(any(), any(), any());
        var collector = new MyHomeAnnouncementCollector(
                storage, records, api, CLOCK);
        return new MyHomeAnnouncementCollectionService(
                collector, lifecycle, records, failures, retry, CLOCK);
    }

    LhLeaseCatalogCollectionService lease(
            LhLeaseCatalogExternalRepository external,
            LhStorageFixtures fixtures,
            ExternalDataFailureRecorder failures, ExternalDataRetryExecutor retry
    ) {
        var api = mock(LhLeaseCatalogApiRepository.class);
        var parser = new LhLeaseCatalogPageParser();
        lenient().when(api.fetch(any(), anyInt())).thenAnswer(invocation -> {
            LhLeaseCatalogCollectionRequest request = invocation.getArgument(0);
            var oldRequest = new LhLeaseCatalogCollectionCommand(
                    request.pageSize(), request.maxPages());
            return parser.parse(external.fetch(oldRequest, invocation.getArgument(1)),
                    request, invocation.getArgument(1));
        });
        var storage = mock(LhLeaseCatalogStorageService.class);
        lenient().doAnswer(invocation -> {
            var response = invocation.<LhLeaseCatalogCollectedResponse>getArgument(2);
            var rows = response.rows().stream().map(row -> convert(row,
                    LhCatalogSourceSnapshot.class)).toList();
            storedCounts.put(invocation.getArgument(0), fixtures.replaceCatalog(rows));
            return null;
        }).when(storage).complete(any(), any(), any());
        var collector = new LhLeaseCatalogCollector(
                api, storage, records, CLOCK);
        return new LhLeaseCatalogCollectionService(
                collector, records, failures, retry, CLOCK);
    }

    LhAnnouncementCatalogCollectionService catalog(
            LhAnnouncementExternalRepository external,
            LhAnnouncementCatalogPageParser parser,
            LhCatalogStorageFixtures fixtures,
            ExternalDataFailureRecorder failures, ExternalDataRetryExecutor retry,
            MeterRegistry meters
    ) {
        var api = mock(LhAnnouncementCatalogApiRepository.class);
        lenient().when(api.fetch(any(), anyInt())).thenAnswer(invocation -> {
            var request = invocation.<LhAnnouncementCatalogCollectionRequest>getArgument(0);
            int page = invocation.getArgument(1);
            return parser.parse(external.fetchCatalog(page, request.pageSize()), page, request.pageSize());
        });
        var storage = mock(LhAnnouncementCatalogStorageService.class);
        lenient().when(storage.complete(any(), any(), any())).thenAnswer(invocation -> {
            var request = invocation.<LhAnnouncementCatalogCollectionRequest>getArgument(1);
            var response = invocation.<LhAnnouncementCatalogCollectedResponse>getArgument(2);
            response.validateFor(request);
            var rows = response.rows().stream().map(row ->
                    new LhAnnouncementCatalogPage.Entry(
                            convert(row.snapshot(), LhAnnouncementCatalogSnapshot.class),
                            row.rawPayload())).toList();
            var result = fixtures.store(rows);
            return new LhAnnouncementCatalogStorageService.StoreResult(
                    result.storedRowCount(), result.newRowCount(), result.changedRowCount());
        });
        var collector = new LhAnnouncementCatalogCollector(
                api, storage, records, CLOCK);
        return new LhAnnouncementCatalogCollectionService(
                collector, retry, failures, meters, CLOCK);
    }

    public LhAnnouncementQueryCollector query(
            LhAnnouncementExternalRepository external,
            LhStorageFixtures fixtures
    ) {
        var api = mock(LhAnnouncementQueryApiRepository.class);
        lenient().when(api.supply(any())).thenAnswer(
                invocation -> external.fetchSupply(oldQuery(invocation.getArgument(0))));
        lenient().when(api.detail(any())).thenAnswer(
                invocation -> external.fetchDetail(oldQuery(invocation.getArgument(0))));
        var storage = mock(LhAnnouncementStorageService.class);
        lenient().doAnswer(invocation -> {
            var request = invocation.<LhAnnouncementCollectionRequest>getArgument(1);
            List<LhAnnouncementSupplySourceSnapshot> rows = invocation.getArgument(3);
            var sources = IntStream.range(0, rows.size()).mapToObj(index -> LhAnnouncementSupplySource.read(
                    null, index, request.query().panId(), request.requestHash(),
                    invocation.getArgument(2), rows.get(index))).toList();
            int count = fixtures.replaceSupplies(request.query().panId(), request.description(), sources);
            storedCounts.put(invocation.getArgument(0), count);
            return null;
        }).when(storage).completeSupply(any(), any(), any(), any());
        lenient().doAnswer(invocation -> {
            var request = invocation.<LhAnnouncementCollectionRequest>getArgument(1);
            List<LhAnnouncementDetailSourceSnapshot> rows = invocation.getArgument(3);
            var sources = IntStream.range(0, rows.size()).mapToObj(index -> LhAnnouncementDetailSource.read(
                    null, index, request.query().panId(), request.requestHash(),
                    invocation.getArgument(2), rows.get(index))).toList();
            int count = fixtures.replaceDetails(request.query().panId(), request.description(), sources);
            storedCounts.put(invocation.getArgument(0), count);
            return null;
        }).when(storage).completeDetail(any(), any(), any(), any());
        return new LhAnnouncementQueryCollector(api,
                new LhSupplyResponseParser(),
                new LhDetailResponseParser(), storage, records, CLOCK);
    }

    private LhAnnouncementRequest oldQuery(
            LhAnnouncementQuery query
    ) {
        return new LhAnnouncementRequest(query.panId(),
                query.connectionSystemDivisionCode(), query.upperAnnouncementTypeCode(), query.announcementTypeCode(),
                query.supplyInfoTypeCode());
    }

    private MyHomeRegion region(
            MyHomeRegionCatalog regions, String province, String district
    ) {
        var found = regions.find(province, district);
        if (found != null) {
            return found;
        }
        return regions.findAll().stream().filter(value -> province.equals(value.provinceCode())
                && district.equals(value.districtCode())).findFirst().orElseThrow();
    }

    private <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }
}
