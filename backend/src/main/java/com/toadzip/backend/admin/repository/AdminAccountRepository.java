package com.toadzip.backend.admin.repository;

import com.toadzip.backend.admin.domain.AdminAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    Optional<AdminAccount> findByLoginIdentifier(String loginIdentifier);
}
