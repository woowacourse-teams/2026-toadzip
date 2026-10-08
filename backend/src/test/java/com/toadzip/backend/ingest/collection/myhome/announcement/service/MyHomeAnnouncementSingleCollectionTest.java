package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class MyHomeAnnouncementSingleCollectionTest {

    @Mock private MyHomeAnnouncementStorageService storage;
    @Mock private SourceCollectionRecordService records;
    @Mock private MyHomeAnnouncementApiRepository api;
    @Mock private IngestOperationLock lock;

    @Test
    @SuppressWarnings("unchecked")
    void 없는_ID는_모든_공급유형을_확인한_뒤_실패하며_다른_공고는_저장하지_않는다() {
        when(lock.tryRun(eq(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION), any()))
                .thenAnswer(call -> Optional.of(((Supplier<Integer>) call.getArgument(1)).get()));
        when(records.start(any())).thenAnswer(call -> UUID.randomUUID());
        when(api.fetch(any(), eq(1))).thenReturn(new SourcePage<>(1, List.of(row("other", 1))));

        assertThatThrownBy(() -> collector().collectAnnouncement("missing",
                new ExternalDataRetryExecutor(new SimpleMeterRegistry())))
                .hasMessageContaining("공고를 찾을 수 없습니다");

        ArgumentCaptor<MyHomeAnnouncementCollectedResponse> response = ArgumentCaptor.captor();
        verify(storage, times(7)).complete(any(), any(), response.capture());
        assertThat(response.getAllValues()).allSatisfy(result -> assertThat(result.rows()).isEmpty());
        verify(api, times(7)).fetch(any(), eq(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 외부_호출_제한이면_원천을_저장하지_않고_나머지_유형도_호출하지_않는다() {
        when(lock.tryRun(eq(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION), any()))
                .thenAnswer(call -> Optional.of(((Supplier<Integer>) call.getArgument(1)).get()));
        when(records.start(any())).thenReturn(UUID.randomUUID());
        when(api.fetch(any(), eq(1))).thenThrow(ExternalDataRequestException.rateLimited("limit"));

        assertThatThrownBy(() -> collector().collectAnnouncement("target",
                new ExternalDataRetryExecutor(new SimpleMeterRegistry())))
                .isInstanceOf(com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException.class);

        verify(storage, never()).complete(any(), any(), any());
        verify(api).fetch(any(), eq(1));
        verify(records).fail(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모든_페이지의_대상_공급행만_저장하고_수집_이력에_식별자를_남긴다() {
        var request = request();
        UUID recordId = UUID.randomUUID();
        when(lock.tryRun(eq(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION), any()))
                .thenAnswer(call -> Optional.of(((Supplier<UUID>) call.getArgument(1)).get()));
        when(records.start(any())).thenReturn(recordId);
        when(api.fetch(any(), eq(1))).thenReturn(new SourcePage<>(3, List.of(row("target", 1), row("other", 1))));
        when(api.fetch(any(), eq(2))).thenReturn(new SourcePage<>(3, List.of(row("target", 2))));
        var collector = collector();

        collector.collect(request);

        ArgumentCaptor<MyHomeAnnouncementCollectedResponse> response = ArgumentCaptor.captor();
        ArgumentCaptor<MyHomeAnnouncementCollectionRequest> savedRequest = ArgumentCaptor.captor();
        verify(storage).complete(eq(recordId), savedRequest.capture(), response.capture());
        assertThat(response.getValue().rows()).extracting(MyHomeAnnouncementSourceSnapshot::pblancId)
                .containsExactly("target", "target");
        assertThat(response.getValue().rows()).extracting(MyHomeAnnouncementSourceSnapshot::houseSn)
                .containsExactly(1, 2);
        assertThat(response.getValue().totalCount()).isEqualTo(2);
        assertThat(savedRequest.getValue().parameters()).containsEntry("pblancId", "target");
    }

    private MyHomeAnnouncementCollectionRequest request() {
        return new MyHomeAnnouncementCollectionRequest(null, "01", 2, 1000,
                Instant.parse("2026-10-07T00:00:00Z"), "target");
    }

    private MyHomeAnnouncementCollector collector() {
        return new MyHomeAnnouncementCollector(storage, records, api, lock,
                Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
    }

    private MyHomeAnnouncementSourceSnapshot row(String id, int house) {
        return JsonMapper.builder().build().readValue(
                "{\"pblancId\":\"" + id + "\",\"houseSn\":" + house + "}",
                MyHomeAnnouncementSourceSnapshot.class);
    }
}
