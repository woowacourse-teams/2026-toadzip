package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원천 스냅샷에 묶인 이름 보완값. 수집 원천은 변경하지 않는다. */
@Getter
@Embeddable
@NoArgsConstructor(access = PROTECTED)
public class InitialRegistrationInformation {

    @Column(name = "information_fingerprint", length = 64)
    private String fingerprint;
    @Column(name = "information_complex_name", columnDefinition = "text")
    private String complexName;
    @ElementCollection
    @CollectionTable(name = "initial_registration_type_names",
            joinColumns = @JoinColumn(name = "source_complex_identifier"))
    @MapKeyColumn(name = "source_key", columnDefinition = "text")
    @Column(name = "housing_type_name", nullable = false, columnDefinition = "text")
    private Map<String, String> housingTypeNames = new LinkedHashMap<>();
    @Column(name = "information_evidence", columnDefinition = "text")
    private String evidence;
    @Column(name = "information_checked_by")
    private String checkedBy;
    @Column(name = "information_checked_at")
    private Instant checkedAt;

    public InitialRegistrationInformation(String fingerprint, String complexName, Map<String, String> names,
            String evidence, String actor, Instant now) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}") || evidence == null || evidence.isBlank()
                || actor == null || actor.isBlank() || now == null) {
            throw new IllegalArgumentException("보완값에는 현재 원천과 근거·처리자·시각이 필요합니다.");
        }
        this.fingerprint = fingerprint;
        this.complexName = complexName;
        this.housingTypeNames.putAll(names);
        this.evidence = evidence;
        checkedBy = actor;
        checkedAt = now;
    }

    public boolean matches(String currentFingerprint) {
        return fingerprint != null && fingerprint.equals(currentFingerprint);
    }
}
