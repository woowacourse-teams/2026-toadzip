package com.toadzip.backend.notice;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

	Optional<Notice> findBySourceNoticeId(String sourceNoticeId);

	boolean existsBySourceNoticeId(String sourceNoticeId);

	/**
	 * LH 원천 공고 식별자가 이미 등록되었는지 확인한다.
	 */
	boolean existsBySourcePanId(String sourcePanId);

	Optional<Notice> findBySourcePanId(String sourcePanId);

	/**
	 * 주어진 공고를 이전 버전으로 가리키는 공고를 조회한다.
	 */
	List<Notice> findByBeforeSourceNoticeId(String beforeSourceNoticeId);

	/**
	 * 같은 루트에 속한 공고를 버전 순서로 조회한다.
	 */
	List<Notice> findByRootSourceNoticeIdOrderByVersionNumber(String rootSourceNoticeId);

	/**
	 * 상세 URL에 주어진 문자열이 포함된 공고를 조회한다.
	 */
	List<Notice> findByDetailUrlContaining(String fragment);

}
