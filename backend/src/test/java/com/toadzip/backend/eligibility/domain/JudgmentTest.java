package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.ConditionOutcome.*;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class JudgmentTest {
    @Test
    void 필수_조건이_하나라도_미충족이면_미입력이_있어도_미충족이다() {
        Judgment unknown =
                Judgment.unknown(1, UndeterminedReason.INPUT_MISSING, Set.of(InputFact.BIRTH_DATE));

        Judgment result = Judgment.all(List.of(unknown, Judgment.of(NOT_SATISFIED)));

        assertThat(result.outcome()).isEqualTo(NOT_SATISFIED);
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void 충족된_ANY_안의_미입력은_상위_결과를_막지_않는다() {
        Judgment ignored =
                Judgment.unknown(1, UndeterminedReason.INPUT_MISSING, Set.of(InputFact.BIRTH_DATE));
        Judgment confirmation = Judgment.unknown(2, UndeterminedReason.NOT_IN_INPUT_LIST, Set.of());

        Judgment result =
                Judgment.all(
                        List.of(
                                Judgment.any(List.of(ignored, Judgment.of(SATISFIED))),
                                confirmation));

        assertThat(result.outcome()).isEqualTo(UNDETERMINED);
        assertThat(result.blockers()).containsExactly(2L);
        assertThat(result.missingFacts()).isEmpty();
    }

    @Test
    void ANY가_미래_이행_없이_충족되면_다른_대안의_이행을_요구하지_않는다() {
        Judgment obligation = Judgment.of(NOT_SATISFIED).asObligation(3);

        Judgment result = Judgment.any(List.of(obligation, Judgment.of(SATISFIED)));

        assertThat(result.outcome()).isEqualTo(SATISFIED);
        assertThat(result.obligations()).isEmpty();
    }

    @Test
    void 면제와_적용_없음은_ALL에서_실패나_보류가_아니다() {
        assertThat(
                        Judgment.all(List.of(Judgment.of(EXEMPT), Judgment.of(NOT_APPLICABLE)))
                                .outcome())
                .isEqualTo(SATISFIED);
        assertThat(
                        Judgment.any(List.of(Judgment.of(EXEMPT), Judgment.of(NOT_APPLICABLE)))
                                .outcome())
                .isEqualTo(NOT_SATISFIED);
    }

    @Test
    void 보류된_참조_배제는_자신의_조건을_막은_조건으로_남긴다() {
        Judgment inner = Judgment.unknown(4, UndeterminedReason.MEANING_UNSETTLED, Set.of());

        Judgment result = inner.negate(5);

        assertThat(result.outcome()).isEqualTo(UNDETERMINED);
        assertThat(result.blockers()).containsExactly(5L);
    }
}
