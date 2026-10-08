package com.toadzip.backend.ingest.correction.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "ingest_corrections")
@NoArgsConstructor(access = PROTECTED)
public class IngestCorrection {
    @Id @Column(length = 250)
    private String id;
    @Version
    private long version;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(nullable = false)
    private String actor;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(columnDefinition = "text")
    private String lastFailure;

    public static IngestCorrection create(String id, String payload, String actor) {
        var correction = new IngestCorrection();
        correction.id = id;
        correction.revise(payload, actor);
        return correction;
    }

    public void revise(String payload, String actor) {
        this.payload = payload;
        this.actor = actor;
        updatedAt = Instant.now();
    }

    public void recordOutcome(String failure) {
        lastFailure = failure;
        updatedAt = Instant.now();
    }
}
