package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = PROTECTED)
public class RoadAddressLocationId implements Serializable {

    @Column(name = "road_name_code", nullable = false, length = 12)
    private String roadNameCode;

    @Column(name = "underground", nullable = false, length = 1)
    private String underground;

    @Column(name = "building_main_number", nullable = false)
    private int buildingMainNumber;

    @Column(name = "building_sub_number", nullable = false)
    private int buildingSubNumber;

    @Column(name = "entrance_serial", nullable = false, length = 10)
    private String entranceSerial;

    public RoadAddressLocationId(LocationSummaryRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("위치정보요약DB 레코드는 필수입니다.");
        }
        roadNameCode = record.roadNameCode();
        underground = record.underground();
        buildingMainNumber = record.buildingMainNumber();
        buildingSubNumber = record.buildingSubNumber();
        entranceSerial = record.entranceSerial();
    }
}
