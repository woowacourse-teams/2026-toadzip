package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Python AI 워커와 주고받는 ai_jobs JSON이 저장소의 계약 예시(ai-worker/contract)와 같은 형식인지 확인한다.
 * 서비스가 쓰는 것과 같은 Spring 설정 ObjectMapper로 쓴다.
 */
@JsonTest
class PdfExtractionContractTest {
    private static final Path CONTRACT = Path.of("../ai-worker/contract/announcement-pdf-extraction");

    @Autowired
    private ObjectMapper mapper;

    @Test
    void 작업_입력_스냅숏은_계약_예시와_같은_JSON으로_쓴다() throws Exception {
        var input = new PdfExtractionInput(
                List.of(new PdfExtractionInput.LhSupplyRow(0, "인천부평(도시재생뉴딜) 행복주택", "16A(대학생/청년)",
                        "16.37", "27.6384", "78", "78")));

        assertThat(mapper.readTree(mapper.writeValueAsString(input)))
                .isEqualTo(mapper.readTree(Files.readString(CONTRACT.resolve("input.example.json"))));
    }

    @Test
    void 워커가_쓰는_결과_예시를_모르는_필드_없이_읽는다() throws Exception {
        var strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();

        String example = Files.readString(CONTRACT.resolve("result.example.json"));
        AnnouncementPdfExtraction result = strict.readValue(example, AnnouncementPdfExtraction.class);

        assertThat(mapper.readValue(example, AnnouncementPdfExtraction.class)).isEqualTo(result);

        assertThat(result.json()).startsWith("{\"data\"");
        assertThat(result.pages()).isEqualTo(IntStream.rangeClosed(1, 35).boxed().toList());
        assertThat(result.totalPages()).isEqualTo(35);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.usage()).isEqualTo(new AnnouncementPdfExtraction.Usage(9000, 3000, 500));
    }
}
