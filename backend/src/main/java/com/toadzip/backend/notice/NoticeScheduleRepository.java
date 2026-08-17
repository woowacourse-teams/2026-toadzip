package com.toadzip.backend.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeScheduleRepository extends JpaRepository<NoticeSchedule, Long> {

	@Modifying
	@Query("delete from NoticeSchedule schedule where schedule.notice.id = :noticeId")
	void deleteByNoticeId(@Param("noticeId") Long noticeId);

}
