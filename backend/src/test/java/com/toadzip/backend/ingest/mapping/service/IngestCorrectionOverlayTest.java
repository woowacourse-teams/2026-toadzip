package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IngestCorrectionOverlayTest {
    private final IngestCorrectionOverlay overlay = new IngestCorrectionOverlay(new ObjectMapper());

    @Test
    void preservesUneditedValuesAndAcceptsZero() {
        Map<String, Object> original = Map.of("hsmpNm", "원래 단지", "parkngCo", 10, "pnu", "원천 PNU");
        var corrected = overlay.merge("complex", original, Map.of("parkngCo", 0));
        assertThat(corrected).containsEntry("hsmpNm", "원래 단지").containsEntry("parkngCo", 0);
        assertThat(original).containsEntry("parkngCo", 10);
    }

    @Test
    void rejectsChangingSourceIdentityAndUnknownFields() {
        assertThatThrownBy(() -> overlay.merge("announcement", Map.of(), Map.of("pblancId", "다른 공고")))
                .isInstanceOf(InvalidIngestRequestException.class);
        assertThatThrownBy(() -> overlay.merge("complex", Map.of(), Map.of("unknown", "값")))
                .isInstanceOf(InvalidIngestRequestException.class);
    }
}
