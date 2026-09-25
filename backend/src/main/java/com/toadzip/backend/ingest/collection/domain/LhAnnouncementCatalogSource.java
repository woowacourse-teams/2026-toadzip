package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_catalog_source", indexes = @Index(
        name = "idx_lh_announcement_catalog_pan_id", columnList = "pan_id"
))
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementCatalogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceKey;

    @Column(nullable = false, length = 100)
    private String panId;

    @Column(nullable = false, length = 10)
    private String connectionSystemDivisionCode;

    @Column(nullable = false, length = 10)
    private String upperAnnouncementTypeCode;

    @Column(nullable = false, length = 10)
    private String announcementTypeCode;

    @Column(nullable = false, length = 10)
    private String supplyInfoTypeCode;

    @Column(nullable = false, length = 64)
    private String contentFingerprint;

    @Column(nullable = false, columnDefinition = "text")
    private String rawPayload;

    @Column(nullable = false)
    private Instant changedAt;

    @Column(nullable = false)
    private Instant collectedAt;

    public static LhAnnouncementCatalogSource from(
            LhAnnouncementCatalogSnapshot snapshot, String rawPayload, Instant collectedAt
    ) {
        LhAnnouncementCatalogSource source = new LhAnnouncementCatalogSource();
        source.sourceKey = snapshot.sourceKey();
        source.panId = snapshot.panId();
        source.connectionSystemDivisionCode = snapshot.connectionSystemDivisionCode();
        source.upperAnnouncementTypeCode = snapshot.upperAnnouncementTypeCode();
        source.announcementTypeCode = snapshot.announcementTypeCode();
        source.updateFrom(snapshot, rawPayload, collectedAt);
        return source;
    }

    public boolean updateFrom(LhAnnouncementCatalogSnapshot snapshot, String rawPayload, Instant collectedAt) {
        if (!sourceKey.equals(snapshot.sourceKey())) {
            throw new IllegalArgumentException("다른 LH 공고 목록 원천으로 변경할 수 없습니다.");
        }
        String fingerprint = fingerprintOf(snapshot);
        boolean changed = !fingerprint.equals(contentFingerprint);
        if (changed) {
            changedAt = collectedAt;
        }
        supplyInfoTypeCode = snapshot.supplyInfoTypeCode();
        contentFingerprint = fingerprint;
        this.rawPayload = rawPayload;
        this.collectedAt = collectedAt;
        return changed;
    }

    private static String fingerprintOf(LhAnnouncementCatalogSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> fields = Arrays.asList(
                    snapshot.panId(), snapshot.connectionSystemDivisionCode(), snapshot.upperAnnouncementTypeCode(),
                    snapshot.announcementTypeCode(), snapshot.supplyInfoTypeCode(), snapshot.announcementName(),
                    snapshot.status(), snapshot.noticeDate(), snapshot.publicationDate(), snapshot.closingDate(),
                    snapshot.detailUrl(), snapshot.mobileDetailUrl()
            );
            for (String field : fields) {
                if (field == null) {
                    digest.update("-1:".getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("LH 목록 변경 감지 hash 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
