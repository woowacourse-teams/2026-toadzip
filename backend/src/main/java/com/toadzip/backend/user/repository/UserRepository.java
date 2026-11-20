package com.toadzip.backend.user.repository;

import com.toadzip.backend.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginIdentifier(String loginIdentifier);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.loginIdentifier = :loginIdentifier")
    Optional<User> findForUpdateByLoginIdentifier(String loginIdentifier);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdateById(Long id);

    /** 회원이 없으면 비어 있다. 세션마다 탈퇴 여부와 관리자 여부를 한 번에 확인할 때 쓴다. */
    @Query("select u.admin from User u where u.id = :id")
    Optional<Boolean> findAdminById(Long id);
}
