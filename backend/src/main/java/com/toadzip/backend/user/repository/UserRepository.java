package com.toadzip.backend.user.repository;

import com.toadzip.backend.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginIdentifier(String loginIdentifier);
}
