package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toadzip.backend.housing.domain.RentalType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 워커(applicant_types)와 함께 읽는 계약 예시(applicant-labels.json)로 두 쪽의 신청 대상 이름이 같은지 지킨다. */
class ApplicantLabelsTest {

    private static final Path CONTRACT = Path.of("../ai-worker/contract/announcement-pdf-extraction/applicant-labels.json");

    static Stream<JsonNode> cases() throws IOException {
        JsonNode cases = new ObjectMapper().readTree(CONTRACT.toFile()).path("cases");
        return Stream.iterate(0, index -> index < cases.size(), index -> index + 1).map(cases::get);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void 계약_예시와_같은_신청_대상_이름을_쓴다(JsonNode example) {
        List<String> names = Stream.iterate(0, index -> index < example.path("typeNames").size(), index -> index + 1)
                .map(index -> example.path("typeNames").get(index).asText()).toList();
        RentalType rentalType = example.path("rentalType").isNull() ? null
                : RentalType.valueOf(example.path("rentalType").asText());
        List<String> expected = Stream.iterate(0, index -> index < example.path("labels").size(), index -> index + 1)
                .map(index -> example.path("labels").get(index).asText()).toList();

        assertThat(names.stream().map(name -> ApplicantLabels.of(name, names, rentalType)).toList()).isEqualTo(expected);
    }

    @Test
    void 짝이_없는_표기_없는_줄은_이름을_정하지_않는다() {
        assertThat(ApplicantLabels.of("37", List.of("37"), RentalType.NATIONAL_RENTAL)).isEmpty();
    }
}
