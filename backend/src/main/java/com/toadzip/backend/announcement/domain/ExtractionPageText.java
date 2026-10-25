package com.toadzip.backend.announcement.domain;

import java.util.Arrays;
import java.util.regex.Pattern;

/** PDF 한 쪽의 텍스트. 낱말은 공백을 지운 글에서, 값의 개수는 띄어쓰기를 살린 글에서 찾는다. */
record ExtractionPageText(String compact, String spaced) {

    // 굵은 제목이 "공공공용용용"처럼 같은 글자가 겹쳐 추출되는 경우를 한 글자로 줄인다.
    // 숫자는 2,520,000처럼 같은 숫자가 이어지는 값이 많아 줄이지 않는다.
    private static final Pattern REPEATED = Pattern.compile("([가-힣])\\1{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // PDF에 따라 "- 27 -" 같은 쪽 번호 꼬리말이 본문보다 먼저 추출된다.
    private static final Pattern LEADING_PAGE_NUMBER = Pattern.compile("^-\\d+-");

    ExtractionPageText(String text) {
        this(normalize(text, ""), normalize(text, " "));
    }

    boolean has(ExtractionSection section) {
        return section.matches(this);
    }

    boolean hasAnySection() {
        return Arrays.stream(ExtractionSection.values()).anyMatch(this::has);
    }

    boolean continuesToNextPage() {
        return Arrays.stream(ExtractionSection.values())
                .anyMatch(section -> section.continuesToNextPage() && has(section));
    }

    boolean startsWith(String prefix) {
        return LEADING_PAGE_NUMBER.matcher(compact).replaceFirst("").startsWith(prefix);
    }

    boolean contains(Pattern keyword) {
        return keyword.matcher(compact).find();
    }

    long count(Pattern value) {
        return value.matcher(spaced).results().count();
    }

    private static String normalize(String text, String separator) {
        String collapsed = REPEATED.matcher(text).replaceAll("$1");
        return WHITESPACE.matcher(collapsed).replaceAll(separator).strip();
    }
}
