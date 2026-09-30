package com.toadzip.backend.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "admin_data_changes")
@NoArgsConstructor(access = PROTECTED)
public class AdminDataChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 30) private String resourceType;
    @Column(nullable = false) private long resourceId;
    @Column(nullable = false, length = 30) private String action;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false, columnDefinition = "text") private String beforeValue;
    @Column(nullable = false, columnDefinition = "text") private String afterValue;

    public AdminDataChange(String type, long id, String action, String actor, String before, String after) {
        this.resourceType = type;
        this.resourceId = id;
        this.action = action;
        this.actor = actor;
        this.occurredAt = Instant.now();
        this.beforeValue = before;
        this.afterValue = after;
    }
}
