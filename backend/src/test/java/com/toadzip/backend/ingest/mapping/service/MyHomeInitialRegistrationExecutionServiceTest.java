package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, IngestExecutionExtension.class})
class MyHomeInitialRegistrationExecutionServiceTest {

    @Mock private MyHomeInitialRegistrationService preparation;
    @Mock private InitialRegistrationTargetRepository targets;
    @Mock private MyHomeInitialRegistrationWriter writer;
    @InjectMocks private MyHomeInitialRegistrationExecutionService service;

    @Test
    void 같은_주소의_단지_수와_고유_주소_수를_구분한다() {
        var first = target("1:HAPPY_HOUSING", "서울특별시 종로구 테스트로 1");
        var second = target("2:NATIONAL_RENTAL", "서울특별시 종로구 테스트로 1 (참고)");
        when(targets.findAllByStatusOrderBySourceComplexIdentifier(InitialRegistrationStatus.READY))
                .thenReturn(List.of(first, second));
        when(writer.register(first.getSourceComplexIdentifier(), "admin")).thenReturn(InitialRegistrationStatus.COMPLETED);
        when(writer.register(second.getSourceComplexIdentifier(), "admin")).thenReturn(InitialRegistrationStatus.EXISTING);
        var result = service.register("admin");
        verify(preparation).refresh();
        assertThat(result.targetCount()).isEqualTo(2);
        assertThat(result.uniqueAddressCount()).isEqualTo(1);
        assertThat(result.counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1);
        assertThat(result.counts().get(InitialRegistrationStatus.EXISTING)).isEqualTo(1);
    }

    private InitialRegistrationTarget target(String identifier, String address) {
        var result = InitialRegistrationTarget.create(identifier);
        result.assess("단지", address, InitialRegistrationStatus.READY, "준비됨", null, Instant.EPOCH);
        return result;
    }
}
