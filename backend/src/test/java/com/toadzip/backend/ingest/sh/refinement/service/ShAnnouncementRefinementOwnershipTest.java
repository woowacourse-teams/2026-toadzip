package com.toadzip.backend.ingest.sh.refinement.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSource;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(IngestExecutionExtension.class)
class ShAnnouncementRefinementOwnershipTest {

    @Test
    void 실행_소유권을_잃으면_남은_원천을_시도하지_않고_예외를_전파한다() {
        var sources = mock(ShAnnouncementSourceRepository.class);
        var refiner = mock(ShAnnouncementRefiner.class);
        var guard = mock(IngestWriteOwnershipGuard.class);
        var targets = List.of(source("1"), source("2"), source("3"));
        when(sources.findAll()).thenReturn(targets);
        doThrow(new IngestOwnershipLostException()).when(guard).verifyWrite();
        var service = new ShAnnouncementRefinementService(sources, refiner, mock(PlatformTransactionManager.class), guard);

        assertThatThrownBy(service::refineAll).isInstanceOf(IngestOwnershipLostException.class);

        verify(guard, times(1)).verifyWrite();
        verifyNoInteractions(refiner);
    }

    private static ShAnnouncementSource source(String seq) {
        var source = mock(ShAnnouncementSource.class);
        when(source.getRegisteredDate()).thenReturn(LocalDate.of(2026, 10, 1));
        when(source.getSeq()).thenReturn(seq);
        return source;
    }
}
