package com.toadzip.backend.ingest.collection.board.service;

import com.toadzip.backend.ingest.collection.board.dto.BoardDetail;
import com.toadzip.backend.ingest.collection.board.dto.BoardEntry;
import com.toadzip.backend.ingest.collection.board.dto.BoardPage;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.util.List;
import java.util.Set;

/**
 * 목록 하나를 넘기며 글마다 상세를 받는 기관 게시판이다. 게시판은 목록과 상세를 받아 읽고 저장하는 방법, 저장된 원천과
 * 목록을 비교하는 방법만 정한다. 페이지 순회, 다시 받을지 판단, 요청 간격·재시도·중지 확인, 실패 기록과 보고는
 * {@link BoardSourceCollector}가 맡는다.
 */
public interface BoardSource<E extends BoardEntry, S> {

    ExternalDataSource source();

    /** 진행 표시와 실패 사유에 쓰는 기관 이름이다. */
    String agency();

    /** 진행 표시에서 글을 세는 단위 이름이다. */
    String unit();

    int pageSize();

    /** 목록 페이지 요청을 실패 기록에서 찾는 설명이다. */
    String pageRequest(int page);

    /** 상세 요청을 실패 기록에서 찾는 설명이다. 이 설명으로 열린 실패가 있는 글은 다시 받는다. */
    String detailRequest(String key);

    BoardPage<E> fetchPage(int page);

    /** 이 게시판에서 받을 글인지 정한다. 페이지 사이 중복과 전체 건수는 거르기 전 목록으로 확인한다. */
    default boolean isTarget(E entry) {
        return true;
    }

    /** 목록 값이 저장값과 같고 모든 부분을 읽어 저장한 글의 key다. */
    Set<String> findUnchanged(List<E> entries);

    /** 상세를 다시 받지 않는 글의 목록 값을 저장값에 반영한다. */
    default void updateListValues(List<E> entries) {
    }

    /** 상세를 받아 읽는다. 외부 호출은 모두 requests로 보내고, 상세를 읽지 못하면 저장하지 않도록 예외를 던진다. */
    BoardDetail<S> readDetail(E entry, int page, BoardRequests requests);

    void store(S snapshot);
}
