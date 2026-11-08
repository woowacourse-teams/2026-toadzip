package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, IngestExecutionExtension.class})
class MyHomeInitialRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    @Mock private MyHomeComplexSourceReader sources;
    @Mock private InitialRegistrationTargetRepository targets;
    @Mock private InitialRegistrationProductStore products;
    @Mock private MyHomeInitialRegistrationAssessment assessment;
    @Mock private IngestWriteOwnershipGuard ownership;
    private MyHomeInitialRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeInitialRegistrationService(sources, new MyHomeComplexSourceMapper(), targets, products,
                assessment, ownership, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 완료한_대상은_원천이_변경되거나_사라져도_다시_판정하지_않는다(boolean sourcePresent) {
        var completed = InitialRegistrationTarget.create("123:HAPPY_HOUSING");
        completed.assess("완료 단지", "주소", InitialRegistrationStatus.READY, "준비됨", null, NOW);
        completed.complete(10, NOW);
        when(targets.findAll()).thenReturn(List.of(completed));
        if (sourcePresent) {
            when(sources.findAll()).thenReturn(List.of(source("행복주택", "변경된 주택형")));
        }
        service.refresh();
        verifyNoInteractions(assessment);
        assertThat(completed.getHousingComplexId()).isEqualTo(10);
        assertThat(completed.getStatus()).isEqualTo(InitialRegistrationStatus.COMPLETED);
        verify(ownership).verifyWrite();
    }

    @Test
    void 기존_대상의_원천이_빠져도_삭제하지_않고_확인_사유를_남긴다() {
        var target = InitialRegistrationTarget.create("123:HAPPY_HOUSING");
        target.assess("단지", "주소", InitialRegistrationStatus.READY, "준비됨", null, NOW);
        when(targets.findAll()).thenReturn(List.of(target));
        service.refresh();
        assertThat(target.getStatus()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(target.getDetail()).contains("원천", "누락");
        assertThat(target.isSourcePresent()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 같은_단지의_주택형은_하나의_등록_대상으로_모으고_매입임대는_제외한다() {
        when(sources.findAll()).thenReturn(List.of(source("행복주택", "26A"), source("행복주택", "26B"),
                source("매입임대", "매입임대")));
        when(assessment.assess(any(), any(), any(), any()))
                .thenReturn(new MyHomeInitialRegistrationAssessment.Assessment(
                InitialRegistrationStatus.LOCATION_REQUIRED, "좌표 필요", null));
        service.refresh();
        ArgumentCaptor<Iterable<InitialRegistrationTarget>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(targets).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(target -> {
            assertThat(target.getSourceComplexIdentifier()).isEqualTo("123:HAPPY_HOUSING");
            assertThat(target.getStatus()).isEqualTo(InitialRegistrationStatus.LOCATION_REQUIRED);
        });
    }

    private MyHomeComplexSourceView source(String supply, String style) {
        return MyHomeSourceViews.complex(new MyHomeComplexSourceSnapshot(123L, "LH", "30", "대전광역시", "170",
                "서구", "테스트", "대전광역시 서구 둔산로 100", "3017011300102180007", "19930607", 100, supply,
                style, new BigDecimal("26"), new BigDecimal("12"), "아파트", null, null, null, 100, null, null, null));
    }
}
