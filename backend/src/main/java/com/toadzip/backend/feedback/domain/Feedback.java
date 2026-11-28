package com.toadzip.backend.feedback.domain;

import jakarta.persistence.Version;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(30) default 'OPEN'")
    private FeedbackStatus status = FeedbackStatus.OPEN;

    @Column(nullable = false, columnDefinition = "text default ''")
    private String note = "";

    @Column(nullable = false, columnDefinition = "varchar(30) default ''")
    private String relatedResource = "";
    private Long relatedId;
    private String actor;
    private Instant processedAt;

    @Embedded
    private FeedbackContent content;

    @Column(nullable = false)
    private Instant createdAt;

    protected Feedback() {
    }

    private Feedback(FeedbackContent content, String relatedResource, Long relatedId, Instant createdAt) {
        this.content = content;
        this.relatedResource = relatedResource;
        this.relatedId = relatedId;
        this.createdAt = createdAt;
    }

    /** 사용자가 공고·단지 상세에서 보낸 의견은 그 대상을 함께 남긴다. 대상은 접수 뒤 바뀌지 않는다. */
    public static Feedback submit(String content, String relatedResource, Long relatedId, Instant createdAt) {
        if (relatedResource.isEmpty() != (relatedId == null)) {
            throw new IllegalArgumentException("관련 대상의 종류와 ID를 함께 입력해 주세요.");
        }
        return new Feedback(FeedbackContent.of(content), relatedResource, relatedId, createdAt);
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
    public void process(FeedbackStatus nextStatus, String result, String operator, Instant now) {
        if ((nextStatus == FeedbackStatus.DONE || nextStatus == FeedbackStatus.ON_HOLD) && result.isBlank()) {
            throw new IllegalArgumentException("완료 결과 또는 보류 이유를 입력해 주세요.");
        }
        status = nextStatus;
        note = result;
        actor = operator;
        processedAt = now;
    }

    public long getVersion() { return version; }
    public FeedbackStatus getStatus() { return status; }
    public String getNote() { return note; }
    public String getRelatedResource() { return relatedResource; }
    public Long getRelatedId() { return relatedId; }
    public String getActor() { return actor; }
    public Instant getProcessedAt() { return processedAt; }
}
