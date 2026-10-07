package com.toadzip.backend.streetview.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
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
@Table(name = "street_view_policies")
@NoArgsConstructor(access = PROTECTED)
public class StreetViewPolicy {
    public static final long POLICY_ID = 1L;

    @Id
    private Long id;
    @Column(nullable = false)
    private boolean enabled;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false, length = 500)
    private String changeReason;
    @Column(nullable = false)
    private String updatedBy;
    @Column(nullable = false)
    private Instant updatedAt;

    public static StreetViewPolicy initialize(Instant now) {
        StreetViewPolicy policy = new StreetViewPolicy();
        policy.id = POLICY_ID;
        policy.changeReason = "FE 연동 검증 전 기본 비활성화";
        policy.updatedBy = "SYSTEM_MIGRATION";
        policy.updatedAt = now;
        return policy;
    }

    public boolean revise(boolean requestedEnabled, String reason, String actor, Instant now) {
        if (reason == null || reason.trim().isBlank() || reason.trim().length() > 500) {
            throw new InvalidStreetViewRequestException("reason", "변경 사유는 공백이 아닌 1~500자여야 합니다.");
        }
        String normalizedReason = reason.trim();
        if (enabled == requestedEnabled && changeReason.equals(normalizedReason)) {
            return false;
        }
        if (actor == null || actor.isBlank() || actor.length() > 255) {
            throw new IllegalArgumentException("정책 변경자의 식별자가 유효하지 않습니다.");
        }
        enabled = requestedEnabled;
        changeReason = normalizedReason;
        updatedBy = actor;
        updatedAt = now;
        return true;
    }
}
