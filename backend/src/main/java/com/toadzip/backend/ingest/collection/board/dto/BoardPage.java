package com.toadzip.backend.ingest.collection.board.dto;

import java.util.List;

/** 게시판 목록 한 페이지다. totalCount는 목록 전체 글 수다. */
public interface BoardPage<E extends BoardEntry> {

    int page();

    int totalCount();

    List<E> entries();
}
