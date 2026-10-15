package com.toadzip.backend.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "notification_daily_activity")
public class NotificationDailyActivity {
    @Id
    private LocalDate day;
    @Column(nullable = false)
    private long activations;
    @Column(nullable = false)
    private long cancellations;
    @Column(nullable = false)
    private Instant firstRecordedAt;

    protected NotificationDailyActivity() {
    }
}
