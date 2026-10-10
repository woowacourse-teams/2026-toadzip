package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "sh_announcement_source")
@NoArgsConstructor(access = PROTECTED)
public class ShAnnouncementSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceKey;

    @Column(nullable = false, length = 100)
    private String seq;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String department;

    @Column(nullable = false)
    private LocalDate registeredDate;

    @Column(nullable = false, columnDefinition = "text")
    private String bodyHtml;

    @Column(nullable = false, columnDefinition = "text")
    private String bodyText;

    @Column(nullable = false, columnDefinition = "text")
    private String attachments;

    @Column(nullable = false, columnDefinition = "text")
    private String originalUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String listUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String rawListHtml;

    @Column(nullable = false, columnDefinition = "text")
    private String rawDetailHtml;

    @Column(nullable = false, length = 64)
    private String contentFingerprint;

    @Column(nullable = false)
    private Instant changedAt;

    @Column(nullable = false)
    private Instant collectedAt;

    public static ShAnnouncementSource from(ShAnnouncementSnapshot snapshot, Instant now) {
        ShAnnouncementSource source = new ShAnnouncementSource();
        source.sourceKey = snapshot.sourceKey();
        source.seq = snapshot.seq();
        source.updateFrom(snapshot, now);
        return source;
    }

    public boolean updateFrom(ShAnnouncementSnapshot snapshot, Instant now) {
        if (!sourceKey.equals(snapshot.sourceKey())) {
            throw new IllegalArgumentException("다른 SH 게시글 원천으로 변경할 수 없습니다.");
        }
        String fingerprint = fingerprintOf(snapshot);
        boolean changed = !fingerprint.equals(contentFingerprint);
        if (changed) {
            changedAt = now;
        }
        title = snapshot.title();
        department = snapshot.department();
        registeredDate = snapshot.registeredDate();
        bodyHtml = snapshot.bodyHtml();
        bodyText = snapshot.bodyText();
        attachments = snapshot.attachments();
        originalUrl = snapshot.originalUrl();
        listUrl = snapshot.listUrl();
        rawListHtml = snapshot.rawListHtml();
        rawDetailHtml = snapshot.rawDetailHtml();
        contentFingerprint = fingerprint;
        collectedAt = now;
        return changed;
    }

    private static String fingerprintOf(ShAnnouncementSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> fields = List.of(snapshot.title(), snapshot.department(),
                    snapshot.registeredDate().toString(), snapshot.bodyHtml(), snapshot.attachments());
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SH 원천 변경 감지 hash 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
