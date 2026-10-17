package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.ReviewDocument;
import com.toadzip.backend.admin.quality.repository.ReviewDocumentRepository;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ReviewDocumentService {
    private final ReviewDocumentRepository documents;
    private final QualityTargetService targets;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    public ReviewDocumentService(ReviewDocumentRepository documents, QualityTargetService targets,
            AdminDataChangeRepository changes, ObjectMapper json) {
        this.documents = documents;
        this.targets = targets;
        this.changes = changes;
        this.json = json;
    }

    public List<ReviewDocument> list(QualityResource resource, long id, int page) {
        targets.detail(resource, id);
        return documents.documents(resource, id, page);
    }

    @Transactional
    public String archive(QualityResource resource, long id, String fileName, String sourceUrl,
            byte[] content, String actor) {
        targets.target(targets.detail(resource, id), "basic");
        if (content.length < 5 || content.length > 25 * 1024 * 1024
                || !new String(content, 0, Math.min(content.length, 1024), StandardCharsets.ISO_8859_1)
                        .contains("%PDF-")) {
            throw new IllegalArgumentException("25MB 이하의 PDF 원본만 보관할 수 있습니다.");
        }
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException("원본 파일명을 확인해 주세요.");
        }
        URI url;
        try {
            url = URI.create(sourceUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("공식 HTTP(S) 출처 URL을 입력해 주세요.");
        }
        if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null || url.getUserInfo() != null || sourceUrl.length() > 2048) {
            throw new IllegalArgumentException("공식 HTTP(S) 출처 URL을 입력해 주세요.");
        }
        String safeName = fileName.replace('\\', '_').replace('/', '_');
        String sha256 = hash(content);
        if (documents.archive(resource, id, sha256, safeName, sourceUrl, content, actor) == 1) {
            changes.save(new AdminDataChange(resource.name(), id, "ARCHIVE_DOCUMENT", actor, "{}",
                    json.writeValueAsString(Map.of("fileName", safeName, "sha256", sha256,
                            "sourceUrl", sourceUrl, "byteCount", content.length))));
        }
        return sha256;
    }

    public ReviewDocumentRepository.Content content(QualityResource resource, long id, String sha256) {
        targets.detail(resource, id);
        return documents.content(resource, id, sha256).orElseThrow(ReviewDocumentNotFound::new);
    }

    private String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static class ReviewDocumentNotFound extends RuntimeException { }
}
