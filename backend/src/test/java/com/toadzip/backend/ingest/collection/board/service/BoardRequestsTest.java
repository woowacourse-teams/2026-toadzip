package com.toadzip.backend.ingest.collection.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.CollectionServiceTestFixture;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(IngestExecutionExtension.class)
class BoardRequestsTest {

    private final BoardRequests requests = new BoardRequests(ExternalDataSource.SH_ANNOUNCEMENT,
            CollectionServiceTestFixture.retryWithoutDelay(), Duration.ZERO);

    @Test
    void 호출_수가_상한에_닿으면_멈춰야_한다고_알린다() {
        for (int call = 1; call < BoardRequests.MAX_CALLS; call++) {
            requests.send("요청", () -> "ok");
        }
        assertThat(requests.halted()).isFalse();

        requests.send("요청", () -> "ok");

        assertThat(requests.halted()).isTrue();
    }

    @Test
    void 요청이_연속으로_다섯_번_실패하면_멈춰야_한다고_알린다() {
        for (int failure = 1; failure <= BoardRequests.MAX_CONSECUTIVE_FAILURES; failure++) {
            assertThat(requests.halted()).isFalse();
            assertThatThrownBy(() -> requests.send("요청", () -> {
                throw new ExternalDataRequestException("HTTP 404");
            })).isInstanceOf(ExternalDataCallFailureException.class);
        }

        assertThat(requests.halted()).isTrue();
        assertThat(requests.haltReason()).contains("연속");
    }

    @Test
    void 연속_실패_횟수는_성공하면_다시_센다() {
        for (int round = 0; round < 3; round++) {
            for (int failure = 1; failure < BoardRequests.MAX_CONSECUTIVE_FAILURES; failure++) {
                assertThatThrownBy(() -> requests.send("요청", () -> {
                    throw new ExternalDataRequestException("HTTP 404");
                })).isInstanceOf(ExternalDataCallFailureException.class);
            }
            assertThat(requests.halted()).isFalse();
            requests.send("요청", () -> "ok");
        }
        assertThat(requests.halted()).isFalse();
    }
}
