package com.toadzip.backend.feedback.domain;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private FeedbackContent content;

    @Column(nullable = false)
    private Instant createdAt;

    protected Feedback() {
    }

    private Feedback(FeedbackContent content, Instant createdAt) {
        this.content = content;
        this.createdAt = createdAt;
    }

    public static Feedback submit(String content, Instant createdAt) {
        return new Feedback(FeedbackContent.of(content), createdAt);
    }

    public Long getId() {
        return id;
    }

    public String getContent() {
        return content.value();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
