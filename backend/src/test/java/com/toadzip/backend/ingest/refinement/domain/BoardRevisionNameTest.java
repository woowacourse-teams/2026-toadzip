package com.toadzip.backend.ingest.refinement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class BoardRevisionNameTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "(수정) 다산 국민임대 입주자 모집|다산 국민임대 입주자 모집",
            "[수정]다산 국민임대 입주자 모집|다산 국민임대 입주자 모집",
            "[정정] 다산 국민임대  입주자 모집|다산 국민임대 입주자 모집",
            "다산 국민임대 입주자 모집(정정)|다산 국민임대 입주자 모집",
            "다산 국민임대 입주자 모집 (수정)|다산 국민임대 입주자 모집",
            "다산 국민임대 입주자 모집(수정-접수일정)|다산 국민임대 입주자 모집",
            "(수정) 다산 국민임대 입주자 모집(정정)|다산 국민임대 입주자 모집",
    })
    void SH는_앞뒤_정정_표시를_떼고_이름을_맞춘다(String title, String base) {
        var name = BoardRevisionName.sh(title);

        assertThat(name.isRevision()).isTrue();
        assertThat(name.baseName()).isEqualTo(base);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[정정공고] 다산 국민임대 예비입주자 모집공고|다산 국민임대 예비입주자 모집공고",
            "(수정) 다산 국민임대 예비입주자 모집공고|다산 국민임대 예비입주자 모집공고",
            "다산 국민임대 예비입주자 모집공고 [정정공고]|다산 국민임대 예비입주자 모집공고",
    })
    void GH는_정정공고_수정_표시를_떼고_이름을_맞춘다(String title, String base) {
        var name = BoardRevisionName.gh(title);

        assertThat(name.isRevision()).isTrue();
        assertThat(name.baseName()).isEqualTo(base);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SH|다산 국민임대 입주자 모집 서류심사대상자 발표",
            "SH|다산 국민임대 입주자 모집",
            "SH|[정정공고] 다산 국민임대 입주자 모집",
            "GH|다산 국민임대 예비입주자 모집공고 서류심사대상자 발표",
            "GH|[정정] 다산 국민임대 예비입주자 모집공고",
    })
    void 정정_표시가_없거나_다른_기관의_표시면_정정공고로_보지_않는다(String agency, String title) {
        var name = agency.equals("SH") ? BoardRevisionName.sh(title) : BoardRevisionName.gh(title);

        assertThat(name.isRevision()).isFalse();
        assertThat(name.baseName()).isEqualTo(title.strip().replaceAll("\\s+", " "));
    }

    @Test
    void 정정_표시만_다른_두_이름은_같은_이름으로_본다() {
        assertThat(BoardRevisionName.gh("[정정공고] 모집공고").baseName())
                .isEqualTo(BoardRevisionName.gh("모집공고").baseName());
    }
}
