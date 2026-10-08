package com.toadzip.backend.ingest.collection.paging.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class PagedCollectionBuffer<T> {

    private final String sourceName;
    private final List<T> rows = new ArrayList<>();
    private final Set<List<T>> pages = new HashSet<>();
    private int totalCount = -1;

    public PagedCollectionBuffer(String sourceName) {
        this.sourceName = sourceName;
    }

    public void add(SourcePage<T> page, Consumer<T> rowValidator) {
        if (page.totalCount() < 0) {
            throw new IllegalArgumentException(sourceName + " 페이지의 전체 건수가 올바르지 않습니다.");
        }
        if (totalCount >= 0 && totalCount != page.totalCount()) {
            throw new IllegalArgumentException(sourceName + " 페이지 간 전체 건수가 일치하지 않습니다.");
        }
        if (page.rows().isEmpty() && page.totalCount() != 0) {
            throw new IllegalArgumentException(sourceName + " 수집 중간에 빈 페이지를 받았습니다.");
        }
        if ((long) rows.size() + page.rows().size() > page.totalCount()) {
            throw new IllegalArgumentException(sourceName + " 응답 행 수가 전체 건수를 초과했습니다.");
        }
        page.rows().forEach(rowValidator);
        if (!page.rows().isEmpty() && !pages.add(page.rows())) {
            throw new IllegalArgumentException(sourceName + "에서 같은 응답 페이지가 반복되었습니다.");
        }
        totalCount = page.totalCount();
        rows.addAll(page.rows());
    }

    public boolean isComplete() {
        return totalCount >= 0 && rows.size() == totalCount;
    }

    public SourcePage<T> finish() {
        if (!isComplete()) {
            throw new IllegalArgumentException("최대 페이지까지 조회했지만 " + sourceName + " 전체 수집을 완료하지 못했습니다.");
        }
        return new SourcePage<>(totalCount, rows);
    }
}
