package com.toadzip.backend.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeAttachmentRepository extends JpaRepository<NoticeAttachment, Long> {

	@Modifying
	@Query("delete from NoticeAttachment attachment where attachment.notice.id = :noticeId")
	void deleteByNoticeId(@Param("noticeId") Long noticeId);

}
