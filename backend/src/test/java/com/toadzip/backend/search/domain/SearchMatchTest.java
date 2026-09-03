package com.toadzip.backend.search.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchMatchTest {

    @Test
    void 앞뒤와_연속_공백을_정규화하고_모든_단어가_있어야_일치한다() {
        SearchMatch match = SearchMatch.from("  서울    행복  ");

        assertEquals("서울 행복", match.normalizedQuery());
        assertTrue(match.matches("서울 강남구", "행복주택"));
        assertFalse(match.matches("서울 강남구", "국민임대"));
    }

    @Test
    void 완전_일치_시작_일치_부분_일치_순으로_등급을_계산한다() {
        assertEquals(0, SearchMatch.from("서울").rank("서울"));
        assertEquals(1, SearchMatch.from("서울").rank("서울특별시"));
        assertEquals(2, SearchMatch.from("서울").rank("동대문 서울 주택"));
    }
}
