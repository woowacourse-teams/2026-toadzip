package com.toadzip.backend.announcement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "admin_supply_corrections")
@NoArgsConstructor(access = PROTECTED)
public class SupplyCorrectionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private UUID operationId;
    @Column(nullable = false) private long announcementId;
    @Column(nullable = false, length = 64) private String requestHash;
    @Column(nullable = false) private long changeId;
    @Column(nullable = false) private long appliedVersion;
    @Column(nullable = false) private Instant createdAt;

    public SupplyCorrectionRecord(UUID operationId, long announcementId, String requestHash,
            long changeId, long appliedVersion) {
        this.operationId = operationId;
        this.announcementId = announcementId;
        this.requestHash = requestHash;
        this.changeId = changeId;
        this.appliedVersion = appliedVersion;
        createdAt = Instant.now();
    }
}
