package com.toadzip.backend.ingest.collection.board.dto;

import java.time.LocalDate;

/** 게시판 목록의 글 한 줄이다. key는 실패 기록과 저장 원천에서 글을 찾는 기관 식별자다. */
public interface BoardEntry {

    String key();

    LocalDate postedDate();
}
