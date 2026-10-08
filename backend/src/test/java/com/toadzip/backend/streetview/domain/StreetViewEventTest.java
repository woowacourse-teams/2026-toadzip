package com.toadzip.backend.streetview.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.streetview.domain.StreetViewEvent.Phase;
import com.toadzip.backend.streetview.domain.StreetViewEvent.ReasonCode;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StreetViewEventTest {
    @ParameterizedTest
    @CsvSource({"STARTED,DOCUMENT,,0", "READY,PANORAMA,,600000", "FAILED,DOCUMENT,DOCUMENT_TIMEOUT,1",
            "FAILED,SDK,SDK_LOAD_FAILED,10", "FAILED,SDK,SDK_AUTH_FAILED,10", "FAILED,SDK,SDK_UNAVAILABLE,10",
            "FAILED,PANORAMA,PANORAMA_QUERY_FAILED,10", "FAILED,DOCUMENT,INITIALIZATION_TIMEOUT,10",
            "FAILED,SDK,INITIALIZATION_TIMEOUT,10", "FAILED,PANORAMA,INITIALIZATION_TIMEOUT,10",
            "CANCELLED,DOCUMENT,USER_CLOSED,0", "CANCELLED,SDK,TARGET_CHANGED,10",
            "CANCELLED,PANORAMA,USER_CLOSED,10"})
    void 유효한_초기화_보고를_허용한다(Type type, Phase phase, ReasonCode reason, long duration) {
        assertThatCode(() -> new StreetViewEvent(UUID.randomUUID(), 1, 0, type, phase, reason, duration))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"STARTED,SDK,,0", "STARTED,DOCUMENT,,1", "STARTED,DOCUMENT,USER_CLOSED,0",
            "READY,SDK,,1", "READY,PANORAMA,SDK_UNAVAILABLE,1", "READY,PANORAMA,,-1",
            "READY,PANORAMA,,600001", "FAILED,SDK,DOCUMENT_TIMEOUT,1", "FAILED,DOCUMENT,SDK_LOAD_FAILED,1",
            "FAILED,SDK,PANORAMA_QUERY_FAILED,1", "FAILED,PANORAMA,,1", "FAILED,PANORAMA,USER_CLOSED,1",
            "CANCELLED,SDK,,1", "CANCELLED,PANORAMA,INITIALIZATION_TIMEOUT,1"})
    void 잘못된_유형_단계_사유_시간을_거부한다(Type type, Phase phase, ReasonCode reason, long duration) {
        assertThatThrownBy(() -> new StreetViewEvent(UUID.randomUUID(), 1, 0, type, phase, reason, duration))
                .isInstanceOf(InvalidStreetViewRequestException.class);
    }
}
