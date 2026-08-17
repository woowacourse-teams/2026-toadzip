package com.toadzip.backend.notice;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeSupplyRepository extends JpaRepository<NoticeSupply, Long> {

	List<NoticeSupply> findByNoticeIdOrderByDisplayOrder(Long noticeId);

	List<NoticeSupply> findByNoticeOrderByDisplayOrder(Notice notice);

	@Modifying
	@Query("delete from NoticeSupply supply where supply.notice.id = :noticeId")
	void deleteByNoticeId(@Param("noticeId") Long noticeId);

}
