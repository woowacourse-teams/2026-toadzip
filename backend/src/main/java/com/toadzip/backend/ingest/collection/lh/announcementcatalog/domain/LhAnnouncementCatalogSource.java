package com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.projection.LhAnnouncementCatalogSnapshot;
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
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementCatalogSource {

    private Long id;

    private String sourceKey;

    private String panId;

    private String connectionSystemDivisionCode;

    private String upperAnnouncementTypeCode;

    private String announcementTypeCode;

    private String supplyInfoTypeCode;

    private String contentFingerprint;

    private String rawPayload;

    private Instant changedAt;

    private Instant collectedAt;

    private boolean presentInLatestCatalog = true;

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

    public static LhAnnouncementCatalogSource read(Long id, Instant changedAt, Instant collectedAt,
            boolean present, String rawPayload,
            com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot snapshot
    ) {
        var normalized = snapshot.normalized();
        var source = from(new LhAnnouncementCatalogSnapshot(normalized.panId(),
                normalized.connectionSystemDivisionCode(), normalized.upperAnnouncementTypeCode(),
                normalized.announcementTypeCode(), normalized.supplyInfoTypeCode(), normalized.announcementName(),
                normalized.status(), normalized.noticeDate(), normalized.publicationDate(), normalized.closingDate(),
                normalized.detailUrl(), normalized.mobileDetailUrl()), rawPayload, collectedAt);
        source.id = id;
        source.changedAt = changedAt;
        source.presentInLatestCatalog = present;
        return source;
    }

    public boolean updateFrom(LhAnnouncementCatalogSnapshot snapshot, String rawPayload, Instant collectedAt) {
        if (!sourceKey.equals(snapshot.sourceKey())) {
            throw new IllegalArgumentException("다른 LH 공고 목록 원천으로 변경할 수 없습니다.");
        }
        String fingerprint = fingerprintOf(snapshot);
        boolean changed = !presentInLatestCatalog || !fingerprint.equals(contentFingerprint);
        if (changed) {
            changedAt = collectedAt;
        }
        supplyInfoTypeCode = snapshot.supplyInfoTypeCode();
        contentFingerprint = fingerprint;
        this.rawPayload = rawPayload;
        this.collectedAt = collectedAt;
        presentInLatestCatalog = true;
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
