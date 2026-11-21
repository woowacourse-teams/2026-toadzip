package com.toadzip.backend.announcement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

/**
 * 관리자가 운영 중에 바꾼 PDF 자동 추출 켜짐 여부. 한 행(id 1)만 둔다. 행이 없으면 아무도 바꾸지 않은 것이라
 * 환경 변수 기본값을 쓴다. 쓰기는 PdfAutoExtractionSettingRepository.save의 upsert로만 한다.
 */
@Getter
@Entity
@Table(name = "pdf_auto_extraction_settings")
@NoArgsConstructor(access = PROTECTED)
public class PdfAutoExtractionSetting {
    public static final short ID = 1;

    @Id private Short id;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private String updatedBy;
    @Column(nullable = false) private Instant updatedAt;
}
