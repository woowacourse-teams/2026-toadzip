package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_announcement_mapping_failures")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementMappingFailure extends IngestFailure<MyHomeAnnouncementMappingFailure> {

    private String sourceAnnouncementIdentifier;

    private Integer sourceHouseSerialNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MyHomeAnnouncementMappingFailureReason reason;

    private MyHomeAnnouncementMappingFailure(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            Integer sourceHouseSerialNumber,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        super(sourceKey, reason, detail, occurredAt);
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.sourceHouseSerialNumber = sourceHouseSerialNumber;
        this.reason = reason;
    }

    @Override
    protected void updateObservedDetails(MyHomeAnnouncementMappingFailure observed) {
        sourceAnnouncementIdentifier = observed.sourceAnnouncementIdentifier;
        sourceHouseSerialNumber = observed.sourceHouseSerialNumber;
    }

    public static MyHomeAnnouncementMappingFailure create(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            Integer sourceHouseSerialNumber,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new MyHomeAnnouncementMappingFailure(
                sourceKey,
                sourceAnnouncementIdentifier,
                sourceHouseSerialNumber,
                reason,
                detail,
                occurredAt
        );
    }
}
