package com.toadzip.backend.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceptionPlaceRepository extends JpaRepository<ReceptionPlace, Long> {

	@Modifying
	@Query("delete from ReceptionPlace place where place.notice.id = :noticeId")
	void deleteByNoticeId(@Param("noticeId") Long noticeId);

}
