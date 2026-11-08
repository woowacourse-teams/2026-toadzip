package com.toadzip.backend.ingest.collection.board.service;

import java.util.List;

/**
 * 게시판 목록 한 페이지(또는 이어받은 미완료 대상)를 처리한 직후 불린다. keys는 그 처리에서 고른 글의 key로, 상세를
 * 새로 저장한 글과 상세를 다시 받지 않은 글을 모두 담는다. 상세를 받지 못한 글은 저장된 원천이 없을 수 있다.
 */
@FunctionalInterface
public interface BoardPageListener {

    BoardPageListener NONE = keys -> {
    };

    void collected(List<String> keys);
}
