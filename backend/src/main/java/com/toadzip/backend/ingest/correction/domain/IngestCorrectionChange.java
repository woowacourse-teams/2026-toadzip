package com.toadzip.backend.ingest.correction.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "ingest_correction_changes")
@NoArgsConstructor(access = PROTECTED)
public class IngestCorrectionChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 250)
    private String target;
    @Column(nullable = false)
    private String actor;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, columnDefinition = "text")
    private String beforeValue;
    @Column(nullable = false, columnDefinition = "text")
    private String afterValue;

    public IngestCorrectionChange(String target, String actor, String before, String after) {
        this.target = target;
        this.actor = actor;
        beforeValue = before;
        afterValue = after;
        occurredAt = Instant.now();
    }
}
