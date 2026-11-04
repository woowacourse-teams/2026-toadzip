package com.toadzip.backend.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedbackContentTest {

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0", "\u202f", "\ufeff", " \n\t"})
    void 선택_의견의_공백을_빈_문자열로_저장한다(String content) {
        assertThat(FeedbackContent.of(content).value()).isEmpty();
    }

    @Test
    void 양끝_공백을_제거하되_본문의_공백과_줄바꿈은_그대로_보존한다() {
        FeedbackContent content = FeedbackContent.of("\u00a0\ufeff지도  검색\n지역 선택\u202f");

        assertThat(content.value()).isEqualTo("지도  검색\n지역 선택");
    }

    @Test
    void 이모지의_길이도_프론트엔드와_같이_UTF16_기준으로_제한한다() {
        assertThat(FeedbackContent.of("😀".repeat(1000)).value()).hasSize(2000);
        assertThatThrownBy(() -> FeedbackContent.of("😀".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
