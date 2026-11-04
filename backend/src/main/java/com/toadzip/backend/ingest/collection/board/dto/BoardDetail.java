package com.toadzip.backend.ingest.collection.board.dto;

import java.util.List;

/** 상세에서 읽어 저장할 원천 값과 읽지 못한 부분이다. 읽지 못한 부분이 있으면 저장한 뒤 실패를 열어 둔다. */
public record BoardDetail<S>(S snapshot, List<String> unreadParts) {

    public BoardDetail {
        unreadParts = List.copyOf(unreadParts);
    }
}
