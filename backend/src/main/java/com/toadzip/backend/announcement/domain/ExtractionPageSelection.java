package com.toadzip.backend.announcement.domain;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * 공고문 PDF에서 추출에 필요한 쪽(1부터 시작)을 고른다.
 * 공급표나 임대조건표를 하나도 찾지 못하면 쪽 선별을 믿지 않고 전체 쪽을 쓴다.
 */
public record ExtractionPageSelection(List<Integer> pageNumbers, boolean wholeDocument) {

    private static final String FORM_PREFIX = "[별지";

    public static ExtractionPageSelection select(List<String> pageTexts) {
        List<ExtractionPageText> pages = pageTexts.stream().map(ExtractionPageText::new).toList();
        if (!coversRequiredSections(pages)) {
            return new ExtractionPageSelection(allPages(pages.size()), true);
        }
        SortedSet<Integer> selected = new TreeSet<>(List.of(1));
        IntStream.range(0, pages.size())
                .filter(index -> isSelectable(pages.get(index)) && pages.get(index).hasAnySection())
                .forEach(index -> selected.addAll(withFollowingPage(pages, index)));
        return new ExtractionPageSelection(List.copyOf(selected), false);
    }

    private static boolean coversRequiredSections(List<ExtractionPageText> pages) {
        return ExtractionSection.required().stream()
                .allMatch(section -> pages.stream().anyMatch(page -> isSelectable(page) && page.has(section)));
    }

    private static Set<Integer> withFollowingPage(List<ExtractionPageText> pages, int index) {
        int pageNumber = index + 1;
        if (!pages.get(index).continuesToNextPage() || pageNumber == pages.size()
                || !isSelectable(pages.get(index + 1))) {
            return Set.of(pageNumber);
        }
        // 표가 다음 쪽으로 이어지면 이어진 쪽에는 머리글이 없어 규칙에 걸리지 않는다.
        return Set.of(pageNumber, pageNumber + 1);
    }

    private static boolean isSelectable(ExtractionPageText page) {
        return !page.startsWith(FORM_PREFIX);
    }

    private static List<Integer> allPages(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }
}
