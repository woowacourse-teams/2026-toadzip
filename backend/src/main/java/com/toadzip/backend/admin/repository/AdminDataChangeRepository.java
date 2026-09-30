package com.toadzip.backend.admin.repository;

import com.toadzip.backend.admin.domain.AdminDataChange;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminDataChangeRepository extends JpaRepository<AdminDataChange, Long> {
    List<AdminDataChange> findByResourceTypeAndResourceIdOrderByIdDesc(String type, long id, Pageable pageable);
}
