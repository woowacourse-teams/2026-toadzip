package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.PdfAutoExtractionSetting;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PdfAutoExtractionSettingRepository extends Repository<PdfAutoExtractionSetting, Short> {
    Optional<PdfAutoExtractionSetting> findById(Short id);

    /** 처음 바꾸는 두 관리자가 동시에 넣어도 기본 키 충돌 없이 나중 값이 남는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO pdf_auto_extraction_settings (id, enabled, updated_by, updated_at)
            VALUES (1, :enabled, :admin, :now)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void save(boolean enabled, String admin, Instant now);
}
