package com.toadzip.backend.ingest.pipeline.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ingest_execution_ownership")
@NoArgsConstructor(access = PROTECTED)
public class IngestExecutionOwnership {

    @Id
    private short id;

    @Column(nullable = false)
    private long generation;

    private UUID ownerId;
}
